package com.damKemon.dam.kemon.service;

import com.damKemon.dam.kemon.indexer.BulkIndexer;
import com.damKemon.dam.kemon.model.ScrapingJob;
import com.damKemon.dam.kemon.repository.ScrapingJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Legacy "trigger a scrape" endpoint kept so the Dashboard "Quick scrape"
 * button still works. The implementation now just kicks the {@link BulkIndexer}
 * — there's no per-query scraping anymore in the DB-first architecture.
 */
@Service
public class ScrapingService {

    private static final Logger log = LoggerFactory.getLogger(ScrapingService.class);

    private final ScrapingJobRepository scrapingJobRepository;
    private final BulkIndexer indexer;

    public ScrapingService(ScrapingJobRepository scrapingJobRepository, BulkIndexer indexer) {
        this.scrapingJobRepository = scrapingJobRepository;
        this.indexer = indexer;
    }

    public ScrapingJob triggerScrape(String query, List<String> sites) {
        ScrapingJob job = ScrapingJob.builder()
                .query(query == null ? "(full reindex)" : query)
                .status("PENDING")
                .sitesRequested(sites != null ? sites : new ArrayList<>())
                .sitesCompleted(new ArrayList<>())
                .startedAt(LocalDateTime.now())
                .build();
        ScrapingJob saved;
        try { saved = scrapingJobRepository.save(job); } catch (DataAccessException e) { saved = job; }
        ScrapingJob ref = saved;

        CompletableFuture.runAsync(() -> {
            try {
                ref.setStatus("RUNNING");
                safeSave(ref);
                BulkIndexer.RunSummary summary = indexer.runAll();
                ref.setStatus("COMPLETED");
                ref.setCompletedAt(LocalDateTime.now());
                log.info("ScrapingService: reindex finished — {} shops, {} products inserted, {} merged",
                        summary.shopsAttempted, summary.productsInserted, summary.productsMerged);
            } catch (Exception e) {
                log.error("ScrapingService: reindex failed", e);
                ref.setStatus("FAILED");
                ref.setErrorMessage(e.getMessage());
                ref.setCompletedAt(LocalDateTime.now());
            }
            safeSave(ref);
        });
        return saved;
    }

    public Optional<ScrapingJob> getJob(String jobId) {
        try { return scrapingJobRepository.findById(jobId); }
        catch (DataAccessException e) { return Optional.empty(); }
    }

    private void safeSave(ScrapingJob job) {
        try { scrapingJobRepository.save(job); } catch (DataAccessException ignored) {}
    }
}
