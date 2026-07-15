package com.damKemon.dam.kemon.indexer;

import com.damKemon.dam.kemon.config.AppRole;
import com.damKemon.dam.kemon.repository.ProductRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Daily indexer trigger. Defaults to 03:00 local time. Disable with
 * {@code INDEXER_SCHEDULED=false} (manual triggers via the admin endpoint
 * still work).
 *
 * <p>Also hosts the <b>catch-up driver</b> ({@link #catchUp()}): a self-driving
 * loop that keeps running full passes until the catalog reaches
 * {@code INDEXER_TARGET_PRODUCTS} (default 30,000), then idles. It fires shortly
 * after each boot/deploy, so a push that auto-deploys is enough to kick it off —
 * no manual trigger needed. It self-stops at the target and backs off if the
 * catalog plateaus, so it never re-crawls every shop forever for nothing.
 */
@Service
public class IndexingScheduler {

    private static final Logger log = LoggerFactory.getLogger(IndexingScheduler.class);

    private final BulkIndexer indexer;
    private final ProductRepository products;
    private final ShopLifecycleScheduler shopLifecycle;
    private final AppRole appRole;

    @Value("${indexer.scheduled:true}")
    private boolean enabled;

    public IndexingScheduler(BulkIndexer indexer,
                             ProductRepository products,
                             ShopLifecycleScheduler shopLifecycle,
                             AppRole appRole) {
        this.indexer = indexer;
        this.products = products;
        this.shopLifecycle = shopLifecycle;
        this.appRole = appRole;
    }

    @Scheduled(cron = "${indexer.cron:0 0 3 * * *}")
    public void nightlyRun() {
        if (appRole.isWeb()) { log.debug("Indexer scheduler skipped — web node never crawls (worker owns it)"); return; }
        if (!enabled) {
            log.info("Indexer scheduler skipped — INDEXER_SCHEDULED is false");
            return;
        }
        log.info("Indexer scheduler firing");
        try {
            BulkIndexer.RunSummary s = indexer.runAll();
            log.info("Indexer scheduler finished: shops={}/{} urls={} inserted={} merged={}",
                    s.shopsSucceeded, s.shopsAttempted, s.urlsScraped, s.productsInserted, s.productsMerged);
        } catch (Exception e) {
            log.error("Indexer scheduler crashed", e);
        }
    }

    /**
     * One hour after the full run, re-fire the indexer just for shops that
     * failed or returned no products. Picks up Daraz/Aarong-style timeouts
     * that would otherwise wait 24h for the next nightly.
     */
    @Scheduled(cron = "${indexer.retry-cron:0 0 4 * * *}")
    public void retryPass() {
        if (appRole.isWeb()) return;
        if (!enabled) return;
        log.info("Indexer retry pass firing");
        try {
            BulkIndexer.RunSummary s = indexer.runRetry();
            log.info("Indexer retry finished: shops={}/{} urls={} inserted={} merged={}",
                    s.shopsSucceeded, s.shopsAttempted, s.urlsScraped, s.productsInserted, s.productsMerged);
        } catch (Exception e) {
            log.error("Indexer retry crashed", e);
        }
    }

    // ─────────────────────────── catch-up driver ───────────────────────────

    @Value("${indexer.catchup-enabled:true}")
    private boolean catchupEnabled;

    /** Stop running once the catalog has at least this many products. */
    @Value("${indexer.target-products:30000}")
    private long targetProducts;

    /** Below this many new products per pass, count a cycle as "no progress". */
    @Value("${indexer.catchup-min-progress:50}")
    private long minProgressPerCycle;

    /** After this many flat cycles, treat the catalog as plateaued and back off. */
    @Value("${indexer.catchup-max-stagnant:3}")
    private int maxStagnantCycles;

    private long lastCount = -1;
    private int stagnantCycles = 0;
    private long invocations = 0;

    /**
     * Catch-up pass. Runs {@code catchup-initial-delay-ms} after boot, then every
     * {@code catchup-interval-ms} after the previous pass <em>completes</em>
     * (fixedDelay ⇒ no overlap, and the spacing is measured from completion so a
     * long pass can't stack on itself).
     */
    @Scheduled(fixedDelayString = "${indexer.catchup-interval-ms:7200000}",
               initialDelayString = "${indexer.catchup-initial-delay-ms:60000}")
    public void catchUp() {
        if (appRole.isWeb()) return;
        if (!enabled || !catchupEnabled) return;
        invocations++;

        long count;
        try {
            count = products.count();
        } catch (Exception e) {
            log.warn("Catch-up: could not read product count: {}", e.getMessage());
            return;
        }

        // Reached the goal — go quiet (log once on the transition).
        if (count >= targetProducts) {
            if (lastCount < targetProducts) {
                log.info("Catch-up: target reached — {} ≥ {} products. Idling; nightly cron keeps it fresh.",
                        count, targetProducts);
            }
            lastCount = count;
            stagnantCycles = 0;
            return;
        }

        // Track progress vs the previous cycle. First-ever cycle counts as progress.
        long progress = (lastCount < 0) ? minProgressPerCycle : (count - lastCount);
        lastCount = count;
        if (progress >= minProgressPerCycle) {
            stagnantCycles = 0;
        } else {
            stagnantCycles++;
        }

        // Plateaued: the current (approved) shops are drained. Don't hammer all
        // shops every cycle — re-probe occasionally to pick up newly approved
        // shops, but otherwise stay idle.
        if (stagnantCycles >= maxStagnantCycles) {
            boolean probe = (invocations % maxStagnantCycles == 0);
            if (!probe) return;
            log.warn("Catch-up: catalog plateaued at {}/{} products — current shops exhausted. "
                    + "Approve more shops in pending_shops to grow. Running a probe pass.",
                    count, targetProducts);
        } else {
            log.info("Catch-up: {}/{} products — running a full pass.", count, targetProducts);
        }

        // Grow the shop set before crawling, but only activate candidates whose
        // sitemap/homepage probe demonstrates a real product catalog.
        try {
            shopLifecycle.runOnce();
        } catch (Exception e) {
            log.warn("Catch-up: shop lifecycle failed: {}", e.getMessage());
        }

        try {
            BulkIndexer.RunSummary s = indexer.runAll();
            log.info("Catch-up pass done: shops={}/{} urls={} inserted={} merged={}",
                    s.shopsSucceeded, s.shopsAttempted, s.urlsScraped, s.productsInserted, s.productsMerged);
        } catch (Exception e) {
            log.error("Catch-up pass crashed", e);
        }
    }
}
