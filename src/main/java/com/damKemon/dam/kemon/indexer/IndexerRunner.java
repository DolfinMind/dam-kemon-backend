package com.damKemon.dam.kemon.indexer;

import com.damKemon.dam.kemon.config.AppRole;
import com.damKemon.dam.kemon.repository.ProductRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

/**
 * The out-of-process crawl pipeline. Runs ONCE at startup when this process is
 * the {@code worker} ({@code app.role=worker}), then exits — so all the heavy
 * scraping memory (Chromium, parse buffers, the per-run dedup index) is
 * reclaimed by the OS each cycle and can never accumulate inside the long-lived
 * API JVM. Fired by the {@code damkemon-prod-worker.service} unit + nightly timer.
 *
 * <p>On the API ("web") node this is a no-op: the API never crawls.
 *
 * <p>Order mirrors the old in-JVM nightly: discover and probe new shops, full
 * crawl, retry the failures, consolidate duplicate rows, then the
 * cross-shop seller-depth fanout. Each stage is isolated so one failure can't
 * abort the rest. The price-history snapshot + hot-drops rebuild deliberately
 * stay on the web node — that's where their cron + in-memory state live.
 */
@Component
public class IndexerRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(IndexerRunner.class);

    private final AppRole appRole;
    private final ShopLifecycleScheduler shopLifecycle;
    private final BulkIndexer indexer;
    private final CatalogRemergeService remerge;
    private final SellerDepthHarvester sellerDepth;
    private final ProductRepository products;
    private final ConfigurableApplicationContext ctx;

    /** Exit the JVM when the pipeline finishes (the oneshot-timer model). Set
     *  false to keep a long-lived worker alive (e.g. for local debugging). */
    @Value("${app.worker.exit-after-run:true}")
    private boolean exitAfterRun;

    public IndexerRunner(AppRole appRole,
                         ShopLifecycleScheduler shopLifecycle,
                         BulkIndexer indexer,
                         CatalogRemergeService remerge,
                         SellerDepthHarvester sellerDepth,
                         ProductRepository products,
                         ConfigurableApplicationContext ctx) {
        this.appRole = appRole;
        this.shopLifecycle = shopLifecycle;
        this.indexer = indexer;
        this.remerge = remerge;
        this.sellerDepth = sellerDepth;
        this.products = products;
        this.ctx = ctx;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!appRole.isWorker()) return;   // the web node never crawls

        long t0 = System.currentTimeMillis();
        long before = safeCount();
        log.info("Worker pipeline starting — catalog at {} products", before);

        stage("shop-lifecycle",  shopLifecycle::runOnce);
        stage("full-index",      indexer::runAll);
        stage("retry-pass",      indexer::runRetry);
        stage("catalog-remerge", () -> remerge.remerge(false));
        stage("seller-depth",    sellerDepth::run);

        long after = safeCount();
        log.info("Worker pipeline done in {}s — {} -> {} products (+{})",
                (System.currentTimeMillis() - t0) / 1000, before, after, after - before);

        if (exitAfterRun) {
            // Clean Spring shutdown (closes the Mongo client, flushes caches),
            // then hand the OS a 0 exit so the oneshot unit records success.
            System.exit(SpringApplication.exit(ctx, () -> 0));
        }
    }

    private void stage(String name, Runnable body) {
        long s = System.currentTimeMillis();
        try {
            log.info("Worker stage '{}' starting", name);
            body.run();
            log.info("Worker stage '{}' done in {}s", name, (System.currentTimeMillis() - s) / 1000);
        } catch (Throwable e) {   // isolate: one stage's failure must not abort the rest
            log.error("Worker stage '{}' failed: {}", name, e.toString());
        }
    }

    private long safeCount() {
        try { return products.count(); } catch (Exception e) { return -1; }
    }
}
