package com.damKemon.dam.kemon.indexer;

import com.damKemon.dam.kemon.model.Shop;
import com.damKemon.dam.kemon.repository.ProductRepository;
import com.damKemon.dam.kemon.repository.ShopRepository;
import com.damKemon.dam.kemon.scraper.ExtractorRegistry;
import com.damKemon.dam.kemon.scraper.ProductExtractor;
import com.damKemon.dam.kemon.scraper.ScrapedProduct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Demand-driven cross-shop price fanout — the engine that grows BOTH catalog
 * breadth (more tech products) and depth (more sellers per product).
 *
 * <p><b>The problem it solves.</b> The nightly {@link BulkIndexer} crawls each
 * shop's catalog independently, so two shops rarely surface the SAME product in
 * the same run — which is why sellers-per-product sat near 1.0 even with a fix to
 * matching. Price comparison is worthless with one seller per product.
 *
 * <p><b>The inversion.</b> Instead of "crawl a shop, see what it has", this takes
 * a shared set of canonical tech models ({@link TechSeedCatalog} plus known
 * catalog rows that are still seller-shallow) and SEARCHES THE SAME MODEL on every
 * tech shop. Because all shops are queried for identical products, the offers land
 * on one {@code matchKey} and stack as distinct sellers — manufacturing the
 * overlapping supply the catalog was missing. A search that finds a model nobody
 * has indexed yet inserts a brand-new product, so breadth grows too.
 *
 * <p><b>Reuse, not reinvention.</b> Discovery reuses {@link SearchSeedCrawler}'s
 * single-query search; extraction reuses {@link ExtractorRegistry}; the merge runs
 * through {@link BulkIndexer.EnrichSession}, i.e. the exact matchKey/LSH/URL path
 * the nightly indexer uses (cap-per-product, price-outlier trimming, etc.). The
 * only new logic is the fanout + a strict "is this really that model?" gate so a
 * search for "iPhone 15 Pro" can't attach a ৳1,500 case to a ৳150,000 phone.
 *
 * <p>Budgeted and browserless by default so it's safe on the small prod box: a
 * rotating window of models hits a rotating slice of shops each pass, and
 * successive passes sweep the whole set.
 */
@Service
public class SellerDepthHarvester {

    private static final Logger log = LoggerFactory.getLogger(SellerDepthHarvester.class);

    private final ShopRepository shopRepository;
    private final ProductRepository productRepository;
    private final SearchSeedCrawler searchSeedCrawler;
    private final ExtractorRegistry extractors;
    private final BulkIndexer bulkIndexer;

    @Value("${seller-depth.enabled:true}")
    private boolean enabled;

    /** How many distinct models to fire at each shop this pass (the rotating window). */
    @Value("${seller-depth.queries-per-shop:14}")
    private int queriesPerShop;

    /** How many shops to cover per pass; the rest defer to the next pass. */
    @Value("${seller-depth.shops-per-run:50}")
    private int shopsPerRun;

    /** Top-N RELEVANT product URLs to extract per search before giving up on a model/shop. */
    @Value("${seller-depth.candidates-per-search:3}")
    private int candidatesPerSearch;

    /** How many product links to scan off a search page before relevance-ranking them.
     *  Must be generous: a shop's search page lists its whole mega-menu first, so the
     *  actual results sit deep in the link list. */
    @Value("${seller-depth.url-scan-limit:80}")
    private int urlScanLimit;

    /** Politeness pause between two searches against the same shop. */
    @Value("${seller-depth.request-delay-ms:500}")
    private long requestDelayMs;

    /** Wall-clock budget for one pass. */
    @Value("${seller-depth.run-budget-minutes:15}")
    private long runBudgetMinutes;

    /** Render search pages with the headless browser (JS shops). Off = cheap jsoup only. */
    @Value("${seller-depth.use-browser:false}")
    private boolean useBrowser;

    /** Also hunt known catalog rows (in tech categories) that still have fewer than this many sellers. */
    @Value("${seller-depth.shallow-seller-threshold:3}")
    private int shallowSellerThreshold;

    /** Cap on dynamic shallow-product queries mixed into the window per pass. */
    @Value("${seller-depth.max-dynamic-queries:40}")
    private int maxDynamicQueries;

    /** Rotates the model window and the shop slice across passes so everything gets covered. */
    private final AtomicInteger pass = new AtomicInteger(0);

    public SellerDepthHarvester(ShopRepository shopRepository,
                                ProductRepository productRepository,
                                SearchSeedCrawler searchSeedCrawler,
                                ExtractorRegistry extractors,
                                BulkIndexer bulkIndexer) {
        this.shopRepository = shopRepository;
        this.productRepository = productRepository;
        this.searchSeedCrawler = searchSeedCrawler;
        this.extractors = extractors;
        this.bulkIndexer = bulkIndexer;
    }

    /** Result of one fanout pass, surfaced by the admin job panel. */
    public static class Result {
        public int shopsCovered;
        public int searchesRun;
        public int offersMatched;
        public int productsInserted;
        public int sellersAdded;
        public long tookSeconds;
        @Override public String toString() {
            return String.format("shops=%d searches=%d matched=%d inserted=%d sellersAdded=%d took=%ds",
                    shopsCovered, searchesRun, offersMatched, productsInserted, sellersAdded, tookSeconds);
        }
    }

    /**
     * Nightly fanout, 1h before the indexer's full pass so freshly-attached
     * sellers are live the same night. Disable with {@code SELLER_DEPTH_ENABLED=false}.
     */
    @Scheduled(cron = "${seller-depth.cron:0 0 2 * * *}")
    public void scheduled() {
        if (!enabled) return;
        log.info("SellerDepth: scheduled pass firing");
        try { run(); }
        catch (Exception e) { log.error("SellerDepth: scheduled pass crashed", e); }
    }

    /**
     * Run one budgeted fanout pass: a rotating window of canonical models searched
     * across a rotating slice of tech shops, every match merged through the
     * indexer's cross-shop path. Synchronous; safe to call from the admin job
     * trigger or a scheduler.
     */
    public Result run() {
        long startMs = System.currentTimeMillis();
        Result r = new Result();
        if (!enabled) {
            log.info("SellerDepth: disabled (seller-depth.enabled=false)");
            return r;
        }

        List<String> window = buildQueryWindow();
        if (window.isEmpty()) {
            log.warn("SellerDepth: empty query window — nothing to fan out");
            return r;
        }
        List<Shop> shops = pickShops();
        if (shops.isEmpty()) {
            log.info("SellerDepth: no tech shops with a search template to fan out across");
            return r;
        }

        log.info("SellerDepth: pass over {} shops × {} models (browser={})", shops.size(), window.size(), useBrowser);
        BulkIndexer.EnrichSession session = bulkIndexer.openEnrichSession();
        long deadline = startMs + runBudgetMinutes * 60_000L;

        for (Shop shop : shops) {
            if (System.currentTimeMillis() > deadline) {
                log.warn("SellerDepth: {}-min budget hit — deferring {} shops to next pass",
                        runBudgetMinutes, shops.size() - r.shopsCovered);
                break;
            }
            boolean js = useBrowser && Boolean.TRUE.equals(shop.getRequiresJs());
            List<ScrapedProduct> offers = new ArrayList<>();
            for (String model : window) {
                if (System.currentTimeMillis() > deadline) break;
                r.searchesRun++;
                ScrapedProduct hit = findModelInShop(shop, model, js);
                if (hit != null) {
                    offers.add(hit);
                    r.offersMatched++;
                }
                sleep(requestDelayMs);
            }
            if (!offers.isEmpty()) {
                try {
                    bulkIndexer.enrich(session, shop, offers);
                } catch (Exception e) {
                    log.debug("SellerDepth: enrich failed for '{}': {}", shop.getSlug(), e.getMessage());
                }
            }
            r.shopsCovered++;
        }

        r.productsInserted = session.inserted();
        r.sellersAdded = session.merged();
        r.tookSeconds = (System.currentTimeMillis() - startMs) / 1000;
        log.info("SellerDepth: pass complete — {}", r);
        return r;
    }

    /**
     * Search one model on one shop and return the matched offer, or null.
     *
     * <p>A shop's search page lists its entire mega-menu before the actual results,
     * so the first product-shaped links are navigation, not the model we searched
     * for. We therefore scan many links, keep only those whose slug carries the
     * model's distinctive (digit-bearing) tokens — "rtx-4060", "galaxy-a55", "15"
     * — rank by relevance, and extract just the top few. If nothing relevant
     * surfaces we skip extraction entirely (the search simply didn't carry the
     * model), which is both correct and much faster than grinding nav pages.
     */
    private ScrapedProduct findModelInShop(Shop shop, String model, boolean js) {
        List<String> urls;
        try {
            urls = searchSeedCrawler.searchProductUrls(shop, model, js, urlScanLimit);
        } catch (Exception e) {
            return null;
        }
        if (urls.isEmpty()) return null;

        List<String> ranked = rankByRelevance(urls, model);
        int tried = 0;
        for (String url : ranked) {
            if (tried >= candidatesPerSearch) break;
            tried++;
            try {
                ProductExtractor extractor = extractors.pickForShop(url, shop);
                ScrapedProduct sp = extractor.extract(url, js);
                if (sp == null || sp.getName() == null || sp.getPrice() == null || sp.getPrice() < 10) continue;
                if (!isSameModel(model, sp.getName())) continue;
                if (sp.getProductUrl() == null) sp.setProductUrl(url);
                return sp;
            } catch (Exception e) {
                log.debug("SellerDepth: extract failed {} ({}): {}", url, shop.getSlug(), e.getMessage());
            }
        }
        return null;
    }

    /**
     * Keep only the product URLs whose slug carries the model's distinctive tokens,
     * ranked most-relevant first. "Distinctive" = digit-bearing tokens (model
     * numbers: 4060, a55, 15, m3); if the model has none, fall back to its longest
     * word. URLs that carry none of those are nav/menu/unrelated links and are
     * dropped — that's what stops the harvester from extracting the homepage menu
     * instead of the search result.
     */
    static List<String> rankByRelevance(List<String> urls, String model) {
        List<String> tokens = queryTokens(model);
        List<String> keyTokens = new ArrayList<>();
        for (String t : tokens) if (t.matches(".*\\d.*")) keyTokens.add(t);
        if (keyTokens.isEmpty()) {
            String longest = "";
            for (String t : tokens) if (t.length() > longest.length()) longest = t;
            if (longest.length() >= 4) keyTokens.add(longest);
        }
        if (keyTokens.isEmpty()) return List.of();   // nothing distinctive to match on

        List<String> scored = new ArrayList<>();
        java.util.Map<String, Integer> score = new java.util.HashMap<>();
        for (String url : urls) {
            String lower = url.toLowerCase();
            int key = 0;
            for (String t : keyTokens) if (lower.contains(t)) key++;
            if (key == 0) continue;                  // no model token in the slug → skip
            int extra = 0;
            for (String t : tokens) if (lower.contains(t)) extra++;
            score.put(url, key * 100 + extra);
            scored.add(url);
        }
        scored.sort((a, b) -> Integer.compare(score.get(b), score.get(a)));
        return scored;
    }

    /** Significant tokens of a model name (matching-normalised, length ≥ 2). */
    private static List<String> queryTokens(String model) {
        String n = BulkIndexer.normaliseForMatching(model);
        List<String> out = new ArrayList<>();
        for (String w : n.split(" ")) if (w.length() >= 2) out.add(w);
        return out;
    }

    /**
     * Strict "is this search hit really the model we asked for?" gate. Reuses the
     * indexer's {@code sameProduct} (discriminators must match + enough word
     * overlap), then additionally rejects accessory listings — a search for a
     * phone routinely surfaces its case/charger first, and those must never merge
     * into the phone.
     */
    static boolean isSameModel(String query, String candidate) {
        if (!BulkIndexer.sameProduct(query, candidate)) return false;
        String qn = BulkIndexer.normaliseForMatching(query);
        String cn = BulkIndexer.normaliseForMatching(candidate);
        for (String noise : TechSeedCatalog.ACCESSORY_NOISE) {
            if (cn.contains(noise) && !qn.contains(noise)) return false;
        }
        return true;
    }

    /**
     * The rotating window of models for this pass: a slice of the curated catalog
     * plus a slice of known seller-shallow tech rows. The same window hits every
     * shop in the pass (that's what stacks sellers on one product); successive
     * passes rotate to the next slice so the whole set is covered over time.
     */
    private List<String> buildQueryWindow() {
        List<String> curated = TechSeedCatalog.MODELS;
        int p = pass.getAndIncrement();

        LinkedHashSet<String> window = new LinkedHashSet<>();
        // Curated slice (rotating) — the deterministic backbone.
        int n = curated.size();
        int start = n == 0 ? 0 : Math.floorMod(p * queriesPerShop, n);
        for (int i = 0; i < queriesPerShop && i < n; i++) {
            window.add(curated.get((start + i) % n));
        }
        // Dynamic booster: known tech products that still have too few sellers,
        // most-reviewed first, so we deepen the rows shoppers actually compare.
        for (String name : shallowTechProductNames()) {
            if (window.size() >= queriesPerShop + maxDynamicQueries) break;
            window.add(name);
        }
        return new ArrayList<>(window);
    }

    /** Names of known tech products still under the seller threshold (heap-safe projection). */
    private List<String> shallowTechProductNames() {
        try {
            var page = PageRequest.of(0, Math.max(1, maxDynamicQueries),
                    Sort.by(Sort.Direction.DESC, "totalReviews"));
            List<ProductRepository.NameView> views = productRepository.findShallowByCategoryIn(
                    TechSeedCatalog.TECH_CATEGORIES, shallowSellerThreshold, page);
            List<String> out = new ArrayList<>(views.size());
            for (ProductRepository.NameView v : views) {
                if (v.getName() != null && !v.getName().isBlank()) out.add(v.getName());
            }
            return out;
        } catch (DataAccessException e) {
            log.debug("SellerDepth: shallow-product lookup failed: {}", e.getMessage());
            return List.of();
        }
    }

    /** Active tech shops that expose a search template, rotated so each pass covers a fresh slice. */
    private List<Shop> pickShops() {
        List<Shop> shops;
        try {
            shops = shopRepository.findByStatus("active");
        } catch (DataAccessException e) {
            log.warn("SellerDepth: cannot list shops: {}", e.getMessage());
            return List.of();
        }
        List<Shop> eligible = new ArrayList<>();
        for (Shop s : shops) {
            if (s.getSearchUrlTemplate() == null || !s.getSearchUrlTemplate().contains("{q}")) continue;
            List<String> cats = s.getCategories();
            if (cats == null) continue;
            boolean tech = cats.stream().anyMatch(c -> c != null
                    && TechSeedCatalog.TECH_CATEGORIES.contains(c.toLowerCase()));
            if (tech) eligible.add(s);
        }
        // Richest tech shops first: a shop that yielded many products last run has a
        // deep, well-structured catalog + working search, so it's the best place to
        // FIND a canonical model and stack another seller onto it. (The nightly
        // indexer already handles freshness rotation; here we optimise for overlap
        // yield.) The slice window below still rotates across passes to reach the tail.
        eligible.sort(Comparator.comparing(
                (Shop s) -> s.getLastIndexedCount() == null ? 0 : s.getLastIndexedCount())
                .reversed());
        if (eligible.size() <= shopsPerRun) return eligible;
        int p = pass.get();
        int from = Math.floorMod(p * shopsPerRun, eligible.size());
        List<Shop> slice = new ArrayList<>(shopsPerRun);
        for (int i = 0; i < shopsPerRun; i++) {
            slice.add(eligible.get((from + i) % eligible.size()));
        }
        return slice;
    }

    private static void sleep(long ms) {
        if (ms <= 0) return;
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
