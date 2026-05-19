package com.damKemon.dam.kemon.indexer;

import com.damKemon.dam.kemon.intelligence.MinHashLSH;
import com.damKemon.dam.kemon.intelligence.QueryClassifier;
import com.damKemon.dam.kemon.model.Product;
import com.damKemon.dam.kemon.model.Shop;
import com.damKemon.dam.kemon.model.SitePrice;
import com.damKemon.dam.kemon.repository.ProductRepository;
import com.damKemon.dam.kemon.repository.ShopRepository;
import com.damKemon.dam.kemon.scraper.ExtractorRegistry;
import com.damKemon.dam.kemon.scraper.ProductExtractor;
import com.damKemon.dam.kemon.scraper.ScrapedProduct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Orchestrates the nightly catalog refresh.
 *
 * <p>Per shop:
 * <ol>
 *   <li>If {@link Shop#getSitemapUrl()} is set, use {@link SitemapCrawler} to
 *       fetch every product URL.</li>
 *   <li>If sitemap returned nothing, fall back to the shop's search URL with
 *       a small set of seed queries (TODO: replaced in Phase 2).</li>
 *   <li>For each URL, route to the right {@link ProductExtractor} via
 *       {@link ExtractorRegistry}.</li>
 *   <li>Use {@link MinHashLSH} to find an existing matching product across
 *       shops — if found, append the new {@link SitePrice}; otherwise insert
 *       a new {@link Product}.</li>
 *   <li>Update {@link Shop#setLastIndexedAt}, {@link Shop#setLastIndexedCount}.</li>
 * </ol>
 *
 * <p>Politeness: per-host concurrency capped via a {@link Semaphore}, with
 * randomized inter-request delay. We aim to be a good citizen on every BD
 * shop we crawl.
 */
@Service
public class BulkIndexer {

    private static final Logger log = LoggerFactory.getLogger(BulkIndexer.class);

    private final ShopRepository shopRepository;
    private final ProductRepository productRepository;
    private final SitemapCrawler sitemapCrawler;
    private final HomepageCrawler homepageCrawler;
    private final ExtractorRegistry extractors;
    private final QueryClassifier classifier;

    /** Whether an indexing run is currently in flight. Prevents overlap. */
    private final AtomicLong runningSince = new AtomicLong(0);

    @Value("${indexer.per-host-parallelism:2}")
    private int perHostParallelism;

    @Value("${indexer.global-parallelism:24}")
    private int globalParallelism;

    @Value("${indexer.per-extract-timeout-ms:15000}")
    private long perExtractTimeoutMs;

    @Value("${indexer.max-products-per-shop:500}")
    private int maxProductsPerShop;

    public BulkIndexer(ShopRepository shopRepository,
                       ProductRepository productRepository,
                       SitemapCrawler sitemapCrawler,
                       HomepageCrawler homepageCrawler,
                       ExtractorRegistry extractors,
                       QueryClassifier classifier) {
        this.shopRepository = shopRepository;
        this.productRepository = productRepository;
        this.sitemapCrawler = sitemapCrawler;
        this.homepageCrawler = homepageCrawler;
        this.extractors = extractors;
        this.classifier = classifier;
    }

    /** Snapshot of the latest indexer run, surfaced by the admin endpoint. */
    public static class RunSummary {
        public long startedAtEpochMs;
        public long finishedAtEpochMs;
        public int shopsAttempted;
        public int shopsSucceeded;
        public int shopsFailed;
        public int productsInserted;
        public int productsMerged;
        public int urlsScraped;
        public boolean inProgress;
    }

    private volatile RunSummary lastRun = new RunSummary();

    public RunSummary getLastRun() { return lastRun; }

    /**
     * Synchronously run a full catalog refresh. Returns once every shop is
     * either done or timed out. Guarded against overlapping runs.
     */
    public RunSummary runAll() {
        long now = System.currentTimeMillis();
        if (!runningSince.compareAndSet(0, now)) {
            log.warn("Indexer already running since {} — refusing to start another", runningSince.get());
            return lastRun;
        }
        RunSummary summary = new RunSummary();
        summary.startedAtEpochMs = now;
        summary.inProgress = true;
        lastRun = summary;

        List<Shop> shops;
        try {
            shops = shopRepository.findByStatus("active");
        } catch (DataAccessException e) {
            log.error("Indexer: cannot list shops — {}", e.getMessage());
            summary.inProgress = false;
            summary.finishedAtEpochMs = System.currentTimeMillis();
            runningSince.set(0);
            return summary;
        }

        log.info("Indexer: starting run over {} active shops", shops.size());
        summary.shopsAttempted = shops.size();

        AtomicInteger inserted = new AtomicInteger();
        AtomicInteger merged = new AtomicInteger();
        AtomicInteger urlsTotal = new AtomicInteger();

        // Cross-shop dedup index, built fresh per run. For huge catalogs we'd
        // want this persisted; for ~50k products it fits in memory fine.
        MinHashLSH lsh = new MinHashLSH();
        warmLsh(lsh);

        ConcurrentHashMap<String, Semaphore> hostLocks = new ConcurrentHashMap<>();
        ExecutorService pool = Executors.newFixedThreadPool(globalParallelism);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();

        // Process shops in parallel — the global pool + per-host semaphore are
        // what actually bound throughput. Total time becomes max-single-shop
        // rather than sum-of-all-shops.
        List<java.util.concurrent.CompletableFuture<Void>> shopFutures = new ArrayList<>(shops.size());
        for (Shop shop : shops) {
            shopFutures.add(java.util.concurrent.CompletableFuture.runAsync(() -> {
                try {
                    int got = indexShop(shop, pool, hostLocks, lsh, inserted, merged);
                    urlsTotal.addAndGet(got);
                    shop.setLastIndexedAt(LocalDateTime.now());
                    shop.setLastIndexedCount(got);
                    shop.setLastError(null);
                    safeSave(shop);
                    succeeded.incrementAndGet();
                } catch (Exception e) {
                    log.warn("Indexer: shop '{}' failed: {}", shop.getSlug(), e.getMessage());
                    shop.setLastError(e.getMessage());
                    safeSave(shop);
                    failed.incrementAndGet();
                }
            }, pool));
        }
        try {
            // Cap total run at 30 min so a misbehaving shop can't stall the cron.
            java.util.concurrent.CompletableFuture.allOf(shopFutures.toArray(new java.util.concurrent.CompletableFuture[0]))
                    .get(30, TimeUnit.MINUTES);
        } catch (Exception e) {
            log.warn("Indexer: top-level wait timed out ({})", e.getClass().getSimpleName());
        }
        summary.shopsSucceeded = succeeded.get();
        summary.shopsFailed = failed.get();

        pool.shutdown();
        try { pool.awaitTermination(2, TimeUnit.MINUTES); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        summary.productsInserted = inserted.get();
        summary.productsMerged = merged.get();
        summary.urlsScraped = urlsTotal.get();
        summary.finishedAtEpochMs = System.currentTimeMillis();
        summary.inProgress = false;
        runningSince.set(0);

        log.info("Indexer: run complete. shops={}/{} (failed={}) urls={} inserted={} merged={} took={}s",
                summary.shopsSucceeded, summary.shopsAttempted, summary.shopsFailed,
                summary.urlsScraped, summary.productsInserted, summary.productsMerged,
                (summary.finishedAtEpochMs - summary.startedAtEpochMs) / 1000);
        return summary;
    }

    /** Seed the LSH with already-saved products so cross-run dedup works. */
    private void warmLsh(MinHashLSH lsh) {
        try {
            int count = 0;
            for (Product p : productRepository.findAll()) {
                if (p.getName() != null && p.getId() != null) {
                    lsh.add(p.getId(), normaliseForMatching(p.getName()), p);
                    count++;
                }
            }
            log.info("Indexer: warmed LSH with {} existing products", count);
        } catch (DataAccessException e) {
            log.warn("Indexer: could not warm LSH (Mongo unreachable: {})", e.getMessage());
        }
    }

    private int indexShop(Shop shop,
                          ExecutorService pool,
                          ConcurrentHashMap<String, Semaphore> hostLocks,
                          MinHashLSH lsh,
                          AtomicInteger inserted,
                          AtomicInteger merged) {
        boolean js = Boolean.TRUE.equals(shop.getRequiresJs());
        List<String> urls = new ArrayList<>();
        if (shop.getSitemapUrl() != null && !shop.getSitemapUrl().isBlank()) {
            urls = sitemapCrawler.crawl(shop.getSitemapUrl());
        }
        // Fallback: crawl homepage + category pages for shops without a
        // useful sitemap (BD-Shop, Pickaboo, Othoba, Walton, etc).
        // For SPA shops, use Playwright to render the homepage.
        if (urls.isEmpty() && shop.getBaseUrl() != null && !shop.getBaseUrl().isBlank()) {
            urls = homepageCrawler.crawl(shop.getBaseUrl(), js);
            if (!urls.isEmpty()) {
                log.info("Indexer: shop '{}' falling back to homepage crawl ({} URLs{})",
                        shop.getSlug(), urls.size(), js ? " [js-rendered]" : "");
            }
        }
        if (urls.isEmpty()) {
            log.info("Indexer: shop '{}' yielded no URLs from sitemap or homepage", shop.getSlug());
            return 0;
        }
        if (urls.size() > maxProductsPerShop) {
            log.info("Indexer: shop '{}' has {} URLs, capping at {}", shop.getSlug(), urls.size(), maxProductsPerShop);
            urls = urls.subList(0, maxProductsPerShop);
        }

        final List<String> targetUrls = urls;
        final int total = targetUrls.size();
        log.info("Indexer: shop '{}' → {} URLs", shop.getSlug(), total);
        AtomicInteger processed = new AtomicInteger();
        AtomicInteger localOk = new AtomicInteger();

        List<java.util.concurrent.CompletableFuture<Void>> futures = new ArrayList<>(total);
        for (String url : targetUrls) {
            futures.add(java.util.concurrent.CompletableFuture.runAsync(() -> {
                Semaphore lock = hostLocks.computeIfAbsent(hostOf(url),
                        h -> new Semaphore(perHostParallelism));
                if (!lock.tryAcquire()) {
                    try { lock.acquire(); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt(); return;
                    }
                }
                try {
                    ProductExtractor extractor = extractors.pick(url);
                    ScrapedProduct sp = extractor.extract(url, js);
                    if (sp == null || sp.getName() == null || sp.getPrice() == null) return;
                    // Sanity: BD products under ৳10 are almost always parse errors
                    // (currency unit confusion, leading zeros, etc).
                    if (sp.getPrice() < 10) return;

                    persistOrMerge(sp, url, shop, lsh, inserted, merged);
                    localOk.incrementAndGet();
                } catch (Exception e) {
                    log.debug("Indexer: extract failed for {}: {}", url, e.getMessage());
                } finally {
                    lock.release();
                    int done = processed.incrementAndGet();
                    if (done % 50 == 0) {
                        log.info("Indexer: shop '{}' progress {}/{}", shop.getSlug(), done, total);
                    }
                }
            }, pool));
        }
        try {
            // Bound the total wait to a sensible upper limit so a misbehaving
            // shop can't stall the entire nightly run.
            long maxWaitMs = Math.min(perExtractTimeoutMs * (long) total, 30L * 60_000L);
            java.util.concurrent.CompletableFuture.allOf(futures.toArray(new java.util.concurrent.CompletableFuture[0]))
                    .get(maxWaitMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.warn("Indexer: shop '{}' partial ({}): {}", shop.getSlug(), e.getClass().getSimpleName(), e.getMessage());
        }
        return localOk.get();
    }

    private synchronized void persistOrMerge(ScrapedProduct sp,
                                             String url,
                                             Shop shop,
                                             MinHashLSH lsh,
                                             AtomicInteger inserted,
                                             AtomicInteger merged) {
        SitePrice price = SitePrice.builder()
                .siteName(shop.getName())
                .siteSlug(shop.getSlug())
                .productUrl(url)
                .price(sp.getPrice())
                .originalPrice(sp.getOriginalPrice())
                .discount(discount(sp.getOriginalPrice(), sp.getPrice()))
                .currency("BDT")
                .inStock(sp.getInStock() == null ? true : sp.getInStock())
                .rating(sp.getRating())
                .reviewCount(sp.getReviewCount())
                .lastUpdated(LocalDateTime.now())
                .build();

        // 1. Try exact URL match first (re-crawl of a known URL just refreshes the price)
        Optional<Product> byUrl = safeFindByUrl(url);
        if (byUrl.isPresent()) {
            Product existing = byUrl.get();
            existing.getPrices().removeIf(p -> Objects.equals(p.getProductUrl(), url));
            existing.getPrices().add(price);
            applyDescriptiveFieldsIfMissing(existing, sp);
            recomputeAggregates(existing);
            existing.setLastScraped(LocalDateTime.now());
            existing.setUpdatedAt(LocalDateTime.now());
            safeSave(existing);
            merged.incrementAndGet();
            return;
        }

        // 2. Fuzzy match across shops via LSH. Match on a NORMALISED name so
        //    "Apple AirPods Pro 3 (USB-C)" matches "Apple Airpods Pro 3" matches
        //    "Airpods Pro 3 USB-C". Threshold loosened to 0.42 — most BD shops
        //    pad product names with brand/colour/storage variants that drag
        //    Jaccard down even when the underlying product is the same.
        String normName = normaliseForMatching(sp.getName());
        MinHashLSH.Match match = lsh.findBest(normName, 0.42);
        if (match != null) {
            Product existing = (Product) match.payload();
            existing.getPrices().removeIf(p -> Objects.equals(p.getSiteSlug(), shop.getSlug()));
            existing.getPrices().add(price);
            applyDescriptiveFieldsIfMissing(existing, sp);
            recomputeAggregates(existing);
            existing.setLastScraped(LocalDateTime.now());
            existing.setUpdatedAt(LocalDateTime.now());
            Product saved = safeSave(existing);
            if (saved != null && saved.getId() != null) {
                // Re-add to LSH under the canonical (possibly upgraded) name
                lsh.add(saved.getId(), normaliseForMatching(saved.getName()), saved);
            }
            merged.incrementAndGet();
            return;
        }

        // 3. Brand-new product
        var intent = classifier.classify(sp.getName());
        Product p = Product.builder()
                .name(sp.getName())
                .slug(slugify(sp.getName()))
                .category(intent.primaryCategory().getLabel().toLowerCase())
                .brands(intent.getBrands())
                .imageUrl(sp.getImageUrl())
                .prices(new ArrayList<>(List.of(price)))
                .lastScraped(LocalDateTime.now())
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        recomputeAggregates(p);
        Product saved = safeSave(p);
        if (saved != null && saved.getId() != null) {
            lsh.add(saved.getId(), normaliseForMatching(saved.getName()), saved);
        }
        inserted.incrementAndGet();
    }

    /**
     * Strip the noise that wrecks Jaccard similarity across shops:
     * parenthetical specs, colour suffixes, "with cable / with case" tails,
     * storage qualifiers, marketing words. The output is only used for LSH
     * keying — the original name remains on the Product for display.
     */
    static String normaliseForMatching(String name) {
        if (name == null) return "";
        String s = name.toLowerCase();
        // Drop parenthetical content: "(8/256GB)", "(2nd Generation)", "(USB-C)"
        s = s.replaceAll("\\([^)]*\\)", " ");
        s = s.replaceAll("\\[[^\\]]*\\]", " ");
        // Drop common spec tails: "with cable", "with charging case", etc.
        s = s.replaceAll("\\b(with|w/?)\\s+(retractable\\s+)?(usb[- ]?c\\s+)?cable\\b", " ");
        s = s.replaceAll("\\bwith\\s+charging\\s+case\\b", " ");
        s = s.replaceAll("\\b(price|in)\\s+bangladesh\\b", " ");
        // Drop storage variants — they vary across shops for the same model.
        s = s.replaceAll("\\b\\d+\\s*[/\\\\]\\s*\\d+\\s*(gb|tb|mb)\\b", " ");
        s = s.replaceAll("\\b\\d{2,4}\\s*(gb|tb)\\b", " ");
        s = s.replaceAll("\\b\\d{1,2}\\s*gb\\b", " ");
        // Drop colour suffixes when at end-of-name (titanium, black, white, etc.)
        s = s.replaceAll("\\b(titanium|black|white|silver|gold|blue|red|green|graphite|onyx|natural|desert|midnight)\\b", " ");
        // Drop punctuation, collapse whitespace
        s = s.replaceAll("[^a-z0-9\\s]", " ").replaceAll("\\s+", " ").trim();
        return s.isBlank() ? name.toLowerCase() : s;
    }

    private void applyDescriptiveFieldsIfMissing(Product existing, ScrapedProduct sp) {
        if (existing.getImageUrl() == null && sp.getImageUrl() != null) existing.setImageUrl(sp.getImageUrl());
        if ((existing.getName() == null || existing.getName().length() < sp.getName().length())
                && sp.getName() != null && sp.getName().length() <= 200) {
            // Prefer the longer, more descriptive name when one shop has more detail.
            existing.setName(sp.getName());
        }
    }

    private void recomputeAggregates(Product p) {
        List<SitePrice> prices = p.getPrices();
        if (prices == null || prices.isEmpty()) return;
        double min = Double.MAX_VALUE, max = 0;
        double rsum = 0; int rn = 0; int reviews = 0;
        for (SitePrice sp : prices) {
            if (sp.getPrice() != null) {
                if (sp.getPrice() < min) min = sp.getPrice();
                if (sp.getPrice() > max) max = sp.getPrice();
            }
            if (sp.getRating() != null) { rsum += sp.getRating(); rn++; }
            if (sp.getReviewCount() != null) reviews += sp.getReviewCount();
        }
        p.setLowestPrice(min == Double.MAX_VALUE ? null : min);
        p.setHighestPrice(max == 0 ? null : max);
        p.setAverageRating(rn == 0 ? null : Math.round(rsum / rn * 10.0) / 10.0);
        p.setTotalReviews(reviews == 0 ? null : reviews);
    }

    private Double discount(Double original, Double current) {
        if (original == null || current == null || original <= 0 || original <= current) return null;
        return Math.round((original - current) / original * 1000.0) / 10.0;
    }

    private static String slugify(String name) {
        return name.toLowerCase().replaceAll("[^a-z0-9\\s-]", "")
                .replaceAll("\\s+", "-").replaceAll("-+", "-")
                .replaceAll("^-|-$", "");
    }

    private static String hostOf(String url) {
        try { return URI.create(url).getHost(); } catch (Exception e) { return "_unknown"; }
    }

    private Optional<Product> safeFindByUrl(String url) {
        try { return productRepository.findByPriceUrl(url); }
        catch (DataAccessException e) { return Optional.empty(); }
    }

    private Product safeSave(Product p) {
        try { return productRepository.save(p); }
        catch (DataAccessException e) {
            log.debug("Product save failed (Mongo down?): {}", e.getMessage());
            return null;
        }
    }

    private void safeSave(Shop s) {
        try { shopRepository.save(s); }
        catch (DataAccessException e) {
            log.debug("Shop save failed: {}", e.getMessage());
        }
    }
}
