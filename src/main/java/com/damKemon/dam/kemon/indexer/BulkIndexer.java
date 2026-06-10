package com.damKemon.dam.kemon.indexer;

import com.damKemon.dam.kemon.intelligence.MinHashLSH;
import com.damKemon.dam.kemon.intelligence.QueryClassifier;
import com.damKemon.dam.kemon.model.IndexerRunRecord;
import com.damKemon.dam.kemon.model.Product;
import com.damKemon.dam.kemon.model.Shop;
import com.damKemon.dam.kemon.model.SitePrice;
import com.damKemon.dam.kemon.repository.IndexerRunRepository;
import com.damKemon.dam.kemon.repository.ProductRepository;
import com.damKemon.dam.kemon.repository.ShopRepository;
import com.damKemon.dam.kemon.scraper.ExtractorRegistry;
import com.damKemon.dam.kemon.scraper.ProductExtractor;
import com.damKemon.dam.kemon.scraper.ScrapedProduct;
import com.damKemon.dam.kemon.service.ShopHealthService;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
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
    private final SearchSeedCrawler searchSeedCrawler;
    private final ExtractorRegistry extractors;
    private final QueryClassifier classifier;
    private final ShopHealthService shopHealth;
    private final IndexerRunRepository indexerRunRepository;
    private final ScraperLearningService learner;
    private final List<ShopHarvester> harvesters;
    private final ApiSniffer apiSniffer;
    private final DomCardHarvester domCardHarvester;

    /** Whether an indexing run is currently in flight. Prevents overlap. */
    private final AtomicLong runningSince = new AtomicLong(0);

    /** Remaining browser-render budget for the current run (see maxJsRendersPerRun). */
    private final AtomicInteger jsRenderBudget = new AtomicInteger(0);

    @Value("${indexer.per-host-parallelism:2}")
    private int perHostParallelism;

    @Value("${indexer.global-parallelism:24}")
    private int globalParallelism;

    @Value("${indexer.per-extract-timeout-ms:15000}")
    private long perExtractTimeoutMs;

    @Value("${indexer.max-products-per-shop:500}")
    private int maxProductsPerShop;

    /** Per-run cap on browser renders (sniffer + learner) so a nightly pass can't
     *  wedge on dozens of serial Playwright calls. Beyond it, 0-yield shops defer
     *  to the next run (both paths are 24h-throttled anyway). */
    @Value("${indexer.max-js-renders-per-run:25}")
    private int maxJsRendersPerRun;

    /** Wall-clock budget for a full run (runAll) before it defers the rest to the
     *  next pass. Keeps the tiny prod box's cron bounded; set high on a beefy host
     *  (e.g. a local backfill) so one pass sweeps every shop. runRetry uses 80%. */
    @Value("${indexer.run-budget-minutes:25}")
    private long runBudgetMinutes;

    /** Breadth mode: read rendered DOM cards FIRST for shops that have never
     *  produced (instead of grinding a dead sitemap), to get the long tail of
     *  0-product shops showing fast. Off by default (prod's small box can't
     *  render every shop); enable on a backfill host. */
    @Value("${domcard.first-for-dormant:false}")
    private boolean domCardFirst;

    public BulkIndexer(ShopRepository shopRepository,
                       ProductRepository productRepository,
                       SitemapCrawler sitemapCrawler,
                       HomepageCrawler homepageCrawler,
                       SearchSeedCrawler searchSeedCrawler,
                       ExtractorRegistry extractors,
                       QueryClassifier classifier,
                       ShopHealthService shopHealth,
                       IndexerRunRepository indexerRunRepository,
                       ScraperLearningService learner,
                       List<ShopHarvester> harvesters,
                       ApiSniffer apiSniffer,
                       DomCardHarvester domCardHarvester) {
        this.shopRepository = shopRepository;
        this.productRepository = productRepository;
        this.sitemapCrawler = sitemapCrawler;
        this.homepageCrawler = homepageCrawler;
        this.searchSeedCrawler = searchSeedCrawler;
        this.extractors = extractors;
        this.classifier = classifier;
        this.shopHealth = shopHealth;
        this.indexerRunRepository = indexerRunRepository;
        this.learner = learner;
        this.harvesters = harvesters;
        this.apiSniffer = apiSniffer;
        this.domCardHarvester = domCardHarvester;
    }

    private void persistRunRecord(String kind, RunSummary s) {
        try {
            long secs = (s.finishedAtEpochMs - s.startedAtEpochMs) / 1000;
            indexerRunRepository.save(IndexerRunRecord.builder()
                    .kind(kind)
                    .shopsAttempted(s.shopsAttempted)
                    .shopsSucceeded(s.shopsSucceeded)
                    .shopsFailed(s.shopsFailed)
                    .urlsScraped(s.urlsScraped)
                    .productsInserted(s.productsInserted)
                    .productsMerged(s.productsMerged)
                    .startedAt(Instant.ofEpochMilli(s.startedAtEpochMs))
                    .finishedAt(Instant.ofEpochMilli(s.finishedAtEpochMs))
                    .tookSeconds(secs)
                    .expireAt(Instant.ofEpochMilli(s.finishedAtEpochMs).plusSeconds(60L * 60 * 24 * 90))
                    .build());
        } catch (Exception e) {
            log.debug("IndexerRunRecord persist failed: {}", e.getMessage());
        }
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
        jsRenderBudget.set(maxJsRendersPerRun);

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

        // Rotate coverage: least-recently-indexed shops first (never-indexed get
        // top priority). A time-bounded run otherwise always processes the same
        // head-of-list shops and never reaches the tail — which is why most shops
        // sat at 0 products. Successive runs now sweep the whole shop set.
        shops.sort(Comparator.comparing(Shop::getLastIndexedAt,
                Comparator.nullsFirst(Comparator.naturalOrder())));

        log.info("Indexer: starting run over {} active shops (least-recently-indexed first)", shops.size());
        summary.shopsAttempted = shops.size();

        AtomicInteger inserted = new AtomicInteger();
        AtomicInteger merged = new AtomicInteger();
        AtomicInteger urlsTotal = new AtomicInteger();

        // Cross-shop dedup index, built fresh per run. For huge catalogs we'd
        // want this persisted; for ~50k products it fits in memory fine.
        MinHashLSH lsh = new MinHashLSH();
        warmLsh(lsh);

        ConcurrentHashMap<String, Semaphore> hostLocks = new ConcurrentHashMap<>();
        // One small pool for URL fetches *within* a shop. Shops are processed
        // sequentially below (NOT on this pool), so the blocking wait inside
        // indexShop can never starve its own URL tasks — this fixes the prior
        // same-pool deadlock (shop tasks blocking the threads their URL tasks
        // need) and bounds memory to one shop's working set at a time, which is
        // what makes a run survivable on a 1 GB box.
        ExecutorService urlPool = Executors.newFixedThreadPool(globalParallelism);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();

        long deadline = System.currentTimeMillis() + runBudgetMinutes * 60_000L;
        for (Shop shop : shops) {
            if (System.currentTimeMillis() > deadline) {
                log.warn("Indexer: {}-min budget hit — deferring {} remaining shops to next run",
                        runBudgetMinutes, shops.size() - (succeeded.get() + failed.get()));
                break;
            }
            try {
                int got = indexShop(shop, urlPool, hostLocks, lsh, inserted, merged);
                urlsTotal.addAndGet(got);
                shop.setLastIndexedAt(LocalDateTime.now());
                shop.setLastIndexedCount(got);
                shop.setLastError(null);
                shopHealth.recordRun(shop, got, got == 0 ? "no products extracted" : null);
                safeSave(shop);
                succeeded.incrementAndGet();
            } catch (Exception e) {
                log.warn("Indexer: shop '{}' failed: {}", shop.getSlug(), e.getMessage());
                shop.setLastError(e.getMessage());
                shopHealth.recordRun(shop, 0, e.getMessage());
                safeSave(shop);
                failed.incrementAndGet();
            }
        }
        summary.shopsSucceeded = succeeded.get();
        summary.shopsFailed = failed.get();

        urlPool.shutdown();
        try { urlPool.awaitTermination(2, TimeUnit.MINUTES); }
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
        persistRunRecord("full", summary);
        return summary;
    }

    /**
     * Re-index just the shops whose previous run failed or returned 0 URLs.
     * Cheaper than waiting for the next nightly full pass.
     */
    public RunSummary runRetry() {
        List<Shop> retryShops = shopHealth.shopsNeedingRetry();
        if (retryShops.isEmpty()) {
            log.info("Indexer: retry pass — no shops queued");
            RunSummary empty = new RunSummary();
            empty.startedAtEpochMs = System.currentTimeMillis();
            empty.finishedAtEpochMs = empty.startedAtEpochMs;
            return empty;
        }
        log.info("Indexer: retry pass over {} shops", retryShops.size());
        return runSubset(retryShops);
    }

    /** Synchronously re-index a single shop by slug. */
    public int runOne(String slug) {
        Shop shop;
        try { shop = shopRepository.findBySlug(slug).orElse(null); }
        catch (DataAccessException e) {
            log.warn("Indexer: runOne lookup failed for {}: {}", slug, e.getMessage());
            return 0;
        }
        if (shop == null) {
            log.warn("Indexer: runOne — unknown shop slug '{}'", slug);
            return 0;
        }
        runSubset(List.of(shop));
        return shop.getLastIndexedCount() == null ? 0 : shop.getLastIndexedCount();
    }

    /** Synchronously index a specific set of shops by slug, sharing one LSH warm.
     *  Used to revive dormant shops on demand (e.g. with the browser enabled). */
    public int runShops(List<String> slugs) {
        List<Shop> shops = new ArrayList<>();
        for (String slug : slugs) {
            try { shopRepository.findBySlug(slug).ifPresent(shops::add); }
            catch (DataAccessException ignored) { /* skip */ }
        }
        if (shops.isEmpty()) return 0;
        RunSummary s = runSubset(shops);
        return s.productsInserted + s.productsMerged;
    }

    private RunSummary runSubset(List<Shop> shops) {
        RunSummary summary = new RunSummary();
        summary.startedAtEpochMs = System.currentTimeMillis();
        summary.inProgress = true;
        summary.shopsAttempted = shops.size();
        jsRenderBudget.set(maxJsRendersPerRun);

        MinHashLSH lsh = new MinHashLSH();
        warmLsh(lsh);

        ConcurrentHashMap<String, Semaphore> hostLocks = new ConcurrentHashMap<>();
        // Same model as runAll: sequential shops + a small dedicated URL pool.
        ExecutorService urlPool = Executors.newFixedThreadPool(globalParallelism);
        AtomicInteger inserted = new AtomicInteger();
        AtomicInteger merged = new AtomicInteger();
        AtomicInteger urlsTotal = new AtomicInteger();
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();

        long deadline = System.currentTimeMillis() + (long) (runBudgetMinutes * 0.8) * 60_000L;
        for (Shop shop : shops) {
            if (System.currentTimeMillis() > deadline) {
                log.warn("Indexer: subset budget hit — deferring remaining shops");
                break;
            }
            try {
                int got = indexShop(shop, urlPool, hostLocks, lsh, inserted, merged);
                urlsTotal.addAndGet(got);
                shop.setLastIndexedAt(LocalDateTime.now());
                shop.setLastIndexedCount(got);
                shop.setLastError(null);
                shopHealth.recordRun(shop, got, got == 0 ? "no products extracted" : null);
                safeSave(shop);
                succeeded.incrementAndGet();
            } catch (Exception e) {
                log.warn("Indexer: shop '{}' retry failed: {}", shop.getSlug(), e.getMessage());
                shop.setLastError(e.getMessage());
                shopHealth.recordRun(shop, 0, e.getMessage());
                safeSave(shop);
                failed.incrementAndGet();
            }
        }
        urlPool.shutdown();
        try { urlPool.awaitTermination(1, TimeUnit.MINUTES); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        summary.shopsSucceeded = succeeded.get();
        summary.shopsFailed = failed.get();
        summary.productsInserted = inserted.get();
        summary.productsMerged = merged.get();
        summary.urlsScraped = urlsTotal.get();
        summary.finishedAtEpochMs = System.currentTimeMillis();
        summary.inProgress = false;
        persistRunRecord(shops.size() == 1 ? "single" : "retry", summary);
        return summary;
    }

    /**
     * Seed the LSH with already-saved products so cross-run dedup works.
     * Uses an id+name projection (not full Product docs) and stores only the id
     * as the LSH payload — so heap stays flat regardless of catalog size. The
     * matched Product is loaded on demand in {@link #persistOrMerge}.
     */
    private void warmLsh(MinHashLSH lsh) {
        try {
            int count = 0;
            for (ProductRepository.NameView p : productRepository.findAllNameViews()) {
                if (p.getName() != null && p.getId() != null) {
                    lsh.add(p.getId(), normaliseForMatching(p.getName()), null);
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
        // API-harvested shops (Chaldal, Daraz) skip URL discovery + per-page
        // extraction entirely — their catalog comes from a JSON endpoint. Isolated
        // to shops a harvester claims, so every other shop's path is unchanged.
        for (ShopHarvester h : harvesters) {
            if (h.supports(shop)) {
                int got = harvestApi(shop, h.harvest(shop), lsh, inserted, merged);
                if (got > 0) return got;
                break; // harvester claimed the shop but got nothing → fall through to the normal pipeline
            }
        }

        // Breadth mode: a shop that has never produced via the URL pipeline is
        // almost always JS-rendered/custom/blocked — read its rendered DOM cards
        // FIRST instead of grinding a sitemap that yields nothing (one browser
        // pass beats 1500 dead page fetches). Proven shops skip this and keep the
        // fast sitemap/json-ld path. The 0-yield fallback below won't re-run the
        // DOM harvester for these (guarded), so no shop renders twice.
        if (domCardFirst && domCardHarvester.isEnabled() && neverProduced(shop)
                && jsRenderBudget.getAndDecrement() > 0) {
            try {
                List<ScrapedProduct> cards = domCardHarvester.harvest(shop);
                int got = cards.isEmpty() ? 0 : harvestApi(shop, cards, lsh, inserted, merged);
                if (got > 0) {
                    log.info("Indexer: shop '{}' — DOM-card harvester (first) got {} products", shop.getSlug(), got);
                    return got;
                }
            } catch (Exception e) {
                log.debug("Indexer: DOM-card (first) failed for '{}': {}", shop.getSlug(), e.getMessage());
            }
        }

        boolean js = Boolean.TRUE.equals(shop.getRequiresJs());
        List<String> urls = new ArrayList<>();
        if (shop.getSitemapUrl() != null && !shop.getSitemapUrl().isBlank()) {
            urls = sitemapCrawler.crawl(shop.getSitemapUrl());
        }
        // Fallback 0: auto-discover the sitemap (robots.txt + common paths) when
        // the configured one is missing or came back empty (Yoast/WP/Magento
        // shops that 404 on /sitemap.xml but expose /sitemap_index.xml etc.).
        if (urls.isEmpty() && shop.getBaseUrl() != null && !shop.getBaseUrl().isBlank()) {
            urls = sitemapCrawler.discoverAndCrawl(shop.getBaseUrl());
            if (!urls.isEmpty()) {
                log.info("Indexer: shop '{}' sitemap auto-discovered ({} URLs)", shop.getSlug(), urls.size());
            }
        }
        // Fallback 1: crawl homepage + category pages for shops without a
        // useful sitemap (BD-Shop, Pickaboo, Othoba, Walton, etc).
        // For SPA shops, use Playwright to render the homepage.
        if (urls.isEmpty() && shop.getBaseUrl() != null && !shop.getBaseUrl().isBlank()) {
            urls = homepageCrawler.crawl(shop.getBaseUrl(), js);
            if (!urls.isEmpty()) {
                log.info("Indexer: shop '{}' falling back to homepage crawl ({} URLs{})",
                        shop.getSlug(), urls.size(), js ? " [js-rendered]" : "");
            }
        }
        // Fallback 2: drive the shop's own search URL with category seed
        // queries. Catches retailers whose homepage doesn't directly link
        // products (Walton, Singer, Rangs, Esquire, etc).
        if (urls.isEmpty()) {
            urls = searchSeedCrawler.crawl(shop, js);
            if (!urls.isEmpty()) {
                log.info("Indexer: shop '{}' falling back to search-URL seed crawl ({} URLs)",
                        shop.getSlug(), urls.size());
            }
        }
        if (urls.isEmpty()) {
            log.info("Indexer: shop '{}' yielded no URLs from sitemap, homepage, or search seeds", shop.getSlug());
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
                    ProductExtractor extractor = extractors.pickForShop(url, shop);
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

        // Self-healing for a 0-yield shop — but spend the per-run browser budget
        // so a nightly pass can't queue dozens of serial Playwright renders.
        if (localOk.get() == 0) {
            boolean browserBudget = jsRenderBudget.getAndDecrement() > 0;

            // Magical last resort: render the shop, auto-discover the JSON product
            // feed its own frontend loads, and harvest that (no per-shop code).
            if (browserBudget && apiSniffer.isEnabled()) {
                try {
                    List<ScrapedProduct> sniffed = apiSniffer.sniff(shop);
                    int got = sniffed.isEmpty() ? 0 : harvestApi(shop, sniffed, lsh, inserted, merged);
                    if (got > 0) {
                        log.info("Indexer: shop '{}' — API sniffer rescued {} products", shop.getSlug(), got);
                        return got;
                    }
                } catch (Exception e) {
                    log.debug("Indexer: API sniffer failed for '{}': {}", shop.getSlug(), e.getMessage());
                }
            }

            // "Read the page like a shopper": render the listing pages and read
            // product cards straight from the rendered DOM. Platform-agnostic, so
            // it cracks the JS-rendered / custom-stack / bot-walled shops the
            // feed/json-ld/sniffer paths can't — which is what gets the long tail
            // of 0-product shops to start showing.
            if (browserBudget && domCardHarvester.isEnabled() && !(domCardFirst && neverProduced(shop))) {
                try {
                    List<ScrapedProduct> cards = domCardHarvester.harvest(shop);
                    int got = cards.isEmpty() ? 0 : harvestApi(shop, cards, lsh, inserted, merged);
                    if (got > 0) {
                        log.info("Indexer: shop '{}' — DOM-card harvester rescued {} products", shop.getSlug(), got);
                        return got;
                    }
                } catch (Exception e) {
                    log.debug("Indexer: DOM-card harvester failed for '{}': {}", shop.getSlug(), e.getMessage());
                }
            }

            // Auto-learning diagnosis (also browser-bound; 24h-throttled per shop).
            if (browserBudget) {
                try { learner.learnFromBrokenShop(shop); }
                catch (Exception e) {
                    log.debug("Indexer: learner threw on '{}' (ignored): {}", shop.getSlug(), e.getMessage());
                }
            }
        }
        return localOk.get();
    }

    /**
     * A live merge session that keeps one warmed {@link MinHashLSH} across many
     * per-shop batches, so an out-of-band enrichment pass (the
     * {@link SellerDepthHarvester}) can stream matched offers through the exact
     * same cross-shop matchKey/LSH/URL merge path the nightly indexer uses —
     * without re-reading the catalog for every shop. Open once, feed many shops,
     * read the counters when done.
     */
    public final class EnrichSession {
        private final MinHashLSH lsh = new MinHashLSH();
        private final AtomicInteger inserted = new AtomicInteger();
        private final AtomicInteger merged = new AtomicInteger();
        private EnrichSession() { warmLsh(lsh); }
        public int inserted() { return inserted.get(); }
        public int merged()   { return merged.get(); }
    }

    /** Open an enrichment session (warms the dedup index once). */
    public EnrichSession openEnrichSession() { return new EnrichSession(); }

    /**
     * Merge a batch of matched offers for one shop into the catalog within an
     * open {@link EnrichSession}. New products are inserted (catalog breadth);
     * offers for products we already know attach as additional sellers (depth).
     * Returns the number persisted.
     */
    public int enrich(EnrichSession session, Shop shop, List<ScrapedProduct> offers) {
        if (session == null || shop == null || offers == null || offers.isEmpty()) return 0;
        return harvestApi(shop, offers, session.lsh, session.inserted, session.merged);
    }

    /**
     * Persist products pulled by an API harvester (e.g. Chaldal) directly,
     * reusing the same cross-shop merge path as the URL pipeline. The
     * productUrl carried on each {@link ScrapedProduct} is the dedup key.
     */
    private int harvestApi(Shop shop,
                           List<ScrapedProduct> products,
                           MinHashLSH lsh,
                           AtomicInteger inserted,
                           AtomicInteger merged) {
        int cap = Math.min(products.size(), maxProductsPerShop);
        int ok = 0;
        for (int i = 0; i < cap; i++) {
            ScrapedProduct sp = products.get(i);
            // Same sanity floor the URL pipeline applies: sub-৳10 is almost
            // always a parse error.
            if (sp == null || sp.getName() == null || sp.getPrice() == null || sp.getPrice() < 10) continue;
            String url = sp.getProductUrl() != null ? sp.getProductUrl() : shop.getBaseUrl();
            try {
                persistOrMerge(sp, url, shop, lsh, inserted, merged);
                ok++;
            } catch (Exception e) {
                log.debug("Indexer: shop '{}' API persist failed: {}", shop.getSlug(), e.getMessage());
            }
        }
        log.info("Indexer: shop '{}' API harvest → {} products persisted", shop.getSlug(), ok);
        return ok;
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
                .sellerName(sp.getSellerName())
                .sellerId(sp.getSellerId())
                .soldCount(sp.getSoldCount())
                .lastUpdated(LocalDateTime.now())
                .build();

        String key = productMatchKey(sp.getName());
        String normName = normaliseForMatching(sp.getName());

        // 1. Exact URL match — a re-crawl of a known listing just refreshes that
        //    seller's offer in place.
        Optional<Product> byUrl = safeFindByUrl(url);
        if (byUrl.isPresent()) {
            Product existing = byUrl.get();
            existing.getPrices().removeIf(p -> Objects.equals(p.getProductUrl(), url));
            mergeOffer(existing, price, sp, key, lsh, merged);
            return;
        }

        // 2. Deterministic matchKey — the SAME product from ANY seller, robust
        //    across indexer runs and concurrency (unlike the per-run in-memory
        //    fuzzy index, which let parallel sweeps duplicate the same product).
        //    "Honor 400 (Official)" and "Honor 400" share a key and group;
        //    "GTR 3 Pro" and "GTR 4" don't.
        if (key != null) {
            Optional<Product> byKey = safeFindByKey(key);
            if (byKey.isPresent()) {
                Product existing = byKey.get();
                dropSupersededOffer(existing, price, shop);
                mergeOffer(existing, price, sp, key, lsh, merged);
                return;
            }
        }

        // 3. Fuzzy LSH + sameProduct — catches name variance the exact key misses
        //    (brand-prefix, word order). The MinHash is only a candidate finder;
        //    sameProduct() guards against gluing different models.
        MinHashLSH.Match match = lsh.findBest(normName, 0.50);
        if (match != null) {
            Product existing = null;
            try { existing = productRepository.findById(match.id()).orElse(null); }
            catch (DataAccessException ignored) { /* stale entry → insert fresh */ }
            if (existing != null && sameProduct(existing.getName(), sp.getName())) {
                dropSupersededOffer(existing, price, shop);
                mergeOffer(existing, price, sp, key, lsh, merged);
                return;
            }
        }

        // 4. Brand-new product
        var intent = classifier.classify(sp.getName());
        Product p = Product.builder()
                .name(sp.getName())
                .slug(slugify(sp.getName()))
                .matchKey(key)
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
            lsh.add(saved.getId(), normName, null);
        }
        inserted.incrementAndGet();
    }

    /** Marketplace-aware: drop this seller's superseded offer before re-adding,
     *  so many sellers from one marketplace coexist but a re-crawl replaces (not
     *  duplicates) the same seller's prior offer / legacy seller-less aggregate. */
    private void dropSupersededOffer(Product existing, SitePrice price, Shop shop) {
        final String ok = offerKey(price);
        existing.getPrices().removeIf(p ->
                Objects.equals(offerKey(p), ok)
                || (price.getSellerId() != null
                    && Objects.equals(p.getSiteSlug(), shop.getSlug())
                    && (p.getSellerId() == null || p.getSellerId().isBlank())));
    }

    /** Add the offer to an existing product and persist: cap sellers, fill in
     *  missing descriptive fields, backfill the matchKey, recompute aggregates,
     *  and re-index in the LSH. Caller has already removed any superseded offer. */
    private void mergeOffer(Product existing, SitePrice price, ScrapedProduct sp,
                            String key, MinHashLSH lsh, AtomicInteger merged) {
        existing.getPrices().add(price);
        capSellers(existing);
        applyDescriptiveFieldsIfMissing(existing, sp);
        if (existing.getMatchKey() == null || existing.getMatchKey().isBlank())
            existing.setMatchKey(key != null ? key : productMatchKey(existing.getName()));
        recomputeAggregates(existing);
        existing.setLastScraped(LocalDateTime.now());
        existing.setUpdatedAt(LocalDateTime.now());
        Product saved = safeSave(existing);
        if (saved != null && saved.getId() != null) {
            lsh.add(saved.getId(), normaliseForMatching(saved.getName()), null);
        }
        merged.incrementAndGet();
    }

    private Optional<Product> safeFindByKey(String key) {
        try { return productRepository.findFirstByMatchKey(key); }
        catch (DataAccessException e) { return Optional.empty(); }
    }

    /** Model qualifiers that distinguish otherwise-similar names (S24 vs S24 Ultra,
     *  GTR 3 vs GTR 3 Pro, MacBook Air vs MacBook Pro). */
    private static final java.util.Set<String> MODEL_QUALIFIERS = java.util.Set.of(
            "pro","max","plus","ultra","mini","lite","se","fe","air","fold","flip",
            "neo","prime","note","active","global","gt","ace","turbo","power");

    /**
     * Precise "is this really the same product?" gate, applied AFTER the fuzzy
     * 4-gram MinHash proposes a candidate. The MinHash matches different models
     * that share a brand + a category word, which glued unrelated products — and
     * their prices — into one doc (e.g. "Amazfit Bip U Smart Watch" at ৳5,999 and
     * "Amazfit Balance 2 Smart Watch" at ৳32,990 became one listing). Require the
     * DISCRIMINATING tokens (anything with a digit, plus model qualifiers like
     * pro/max/ultra) to match exactly, plus enough plain word overlap. Storage and
     * colour are already stripped by normaliseForMatching, so genuine variants
     * ("iPhone 15 Pro 256GB" vs "iPhone 15 Pro") still merge. Errs toward NOT
     * merging — a duplicate listing is harmless, a cross-model price merge is not.
     */
    static boolean sameProduct(String a, String b) {
        java.util.Set<String> wa = words(normaliseForMatching(a));
        java.util.Set<String> wb = words(normaliseForMatching(b));
        if (wa.isEmpty() || wb.isEmpty()) return false;
        if (!discriminators(wa).equals(discriminators(wb))) return false;
        java.util.Set<String> inter = new java.util.HashSet<>(wa); inter.retainAll(wb);
        java.util.Set<String> uni = new java.util.HashSet<>(wa); uni.addAll(wb);
        return !uni.isEmpty() && (double) inter.size() / uni.size() >= 0.5;
    }

    private static java.util.Set<String> words(String s) {
        java.util.Set<String> out = new java.util.HashSet<>();
        if (s == null) return out;
        for (String w : s.split(" ")) if (!w.isBlank()) out.add(w);
        return out;
    }

    /** Tokens that pin down a specific model: anything containing a digit, plus
     *  known qualifiers. Two names with different discriminators are different
     *  products even if the MinHash thinks they're similar. */
    private static java.util.Set<String> discriminators(java.util.Set<String> words) {
        java.util.Set<String> out = new java.util.HashSet<>();
        for (String w : words) {
            if (w.matches(".*\\d.*") || MODEL_QUALIFIERS.contains(w)) out.add(w);
        }
        return out;
    }

    /** A shop whose last run produced nothing — the candidates for DOM-first. */
    private static boolean neverProduced(Shop shop) {
        Integer c = shop.getLastIndexedCount();
        return c == null || c <= 0;
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

    /** Edition/region/condition noise that varies between sellers for the SAME
     *  item and must not split it into separate products. (Model numbers and
     *  pro/max/ultra/5g are NOT here — they distinguish real variants.) */
    private static final java.util.regex.Pattern MATCHKEY_NOISE = java.util.regex.Pattern.compile(
            "\\b(official|officially|unofficial|global|international|version|edition"
          + "|limited|special|genuine|original|warranty)\\b");

    /**
     * Deterministic product-identity key for cross-seller grouping. Starts from
     * the matching-normalised name (parens/storage/colour/spec-tails already
     * gone) and additionally strips edition/region/"official"-style noise, so
     * "Honor 400 (Official)" and "Honor 400" collapse to one key while
     * "Amazfit GTR 3 Pro" and "Amazfit GTR 4" stay distinct. Stored on the
     * Product and looked up at persist for run-independent grouping.
     */
    static String productMatchKey(String name) {
        String s = normaliseForMatching(name);
        if (s == null) return null;
        s = MATCHKEY_NOISE.matcher(s).replaceAll(" ").replaceAll("\\s+", " ").trim();
        return s.length() < 2 ? null : s;
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
        double rsum = 0; int rn = 0; int reviews = 0;
        List<Double> vals = new ArrayList<>();
        for (SitePrice sp : prices) {
            if (sp.getPrice() != null && sp.getPrice() > 0) vals.add(sp.getPrice());
            if (sp.getRating() != null) { rsum += sp.getRating(); rn++; }
            if (sp.getReviewCount() != null) reviews += sp.getReviewCount();
        }
        p.setAverageRating(rn == 0 ? null : Math.round(rsum / rn * 10.0) / 10.0);
        p.setTotalReviews(reviews == 0 ? null : reviews);
        if (vals.isEmpty()) { p.setLowestPrice(null); p.setHighestPrice(null); p.setPriceVerdict(null); return; }
        java.util.Collections.sort(vals);

        // Drop EMI / down-payment / parse-error outliers from the HEADLINE price:
        // an offer far below its peers — a ৳10,000 installment on a ৳45,000 phone,
        // or the year "2026" parsed off "...price in Bangladesh 2026" — is not the
        // real price and must never become "Low". (Offers stay in prices[]; only
        // the aggregates ignore the outliers.)
        List<Double> trusted = priceTrusted(vals);
        p.setLowestPrice(trusted.get(0));
        p.setHighestPrice(trusted.get(trusted.size() - 1));

        // Price-truth: rate the cheapest TRUSTED seller against the trusted median.
        if (trusted.size() >= 2) {
            double median = trusted.get(trusted.size() / 2);
            double lo = trusted.get(0);
            if (lo <= median * 0.85) p.setPriceVerdict("real_deal");
            else if (lo >= median * 1.05) p.setPriceVerdict("overpriced");
            else p.setPriceVerdict("fair");
        } else {
            p.setPriceVerdict(null);
        }
    }

    /** Filter EMI/parse-error price outliers for the headline aggregates.
     *  ≥3 offers → trust a sane band around the median; 2 offers → drop only a
     *  blatantly-tiny one (a year/৳1 error); 1 offer → as-is. Never empty. */
    static List<Double> priceTrusted(List<Double> sortedVals) {
        int n = sortedVals.size();
        if (n <= 1) return sortedVals;
        if (n == 2) {
            double a = sortedVals.get(0), b = sortedVals.get(1);
            return a < b * 0.12 ? List.of(b) : sortedVals;   // a is a blatant outlier
        }
        double median = sortedVals.get(n / 2);
        List<Double> trusted = new ArrayList<>();
        for (double v : sortedVals) if (v >= median * 0.35 && v <= median * 4.0) trusted.add(v);
        return trusted.isEmpty() ? sortedVals : trusted;
    }

    private Double discount(Double original, Double current) {
        if (original == null || current == null || original <= 0 || original <= current) return null;
        return Math.round((original - current) / original * 1000.0) / 10.0;
    }

    /** Most offers we keep on one product, so a flooded marketplace can't bloat it. */
    private static final int MAX_OFFERS_PER_PRODUCT = 24;

    /**
     * Dedup identity for an offer. A marketplace sub-seller (sellerId present) is
     * its own offer; a first-party shop has a single offer keyed by its slug — so
     * non-marketplace behaviour is unchanged.
     */
    private static String offerKey(SitePrice p) {
        if (p == null) return "";
        String slug = p.getSiteSlug() == null ? "" : p.getSiteSlug();
        return (p.getSellerId() != null && !p.getSellerId().isBlank())
                ? slug + "#" + p.getSellerId()
                : slug;
    }

    /**
     * Keep a product from ballooning when a marketplace returns dozens of sellers:
     * retain the cheapest in-stock offers, drop the long tail.
     */
    private static void capSellers(Product p) {
        List<SitePrice> prices = p.getPrices();
        if (prices == null || prices.size() <= MAX_OFFERS_PER_PRODUCT) return;
        prices.sort((a, b) -> {
            boolean ai = !Boolean.FALSE.equals(a.getInStock());
            boolean bi = !Boolean.FALSE.equals(b.getInStock());
            if (ai != bi) return ai ? -1 : 1;
            double ap = a.getPrice() == null ? Double.MAX_VALUE : a.getPrice();
            double bp = b.getPrice() == null ? Double.MAX_VALUE : b.getPrice();
            return Double.compare(ap, bp);
        });
        List<SitePrice> kept = new ArrayList<>(prices.subList(0, MAX_OFFERS_PER_PRODUCT));
        prices.clear();
        prices.addAll(kept);
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
