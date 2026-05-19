package com.damKemon.dam.kemon.indexer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Daily indexer trigger. Defaults to 03:00 local time. Disable with
 * {@code INDEXER_SCHEDULED=false} (manual triggers via the admin endpoint
 * still work).
 */
@Service
public class IndexingScheduler {

    private static final Logger log = LoggerFactory.getLogger(IndexingScheduler.class);

    private final BulkIndexer indexer;

    @Value("${indexer.scheduled:true}")
    private boolean enabled;

    public IndexingScheduler(BulkIndexer indexer) {
        this.indexer = indexer;
    }

    @Scheduled(cron = "${indexer.cron:0 0 3 * * *}")
    public void nightlyRun() {
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
}
