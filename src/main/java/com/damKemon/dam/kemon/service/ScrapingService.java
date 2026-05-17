package com.damKemon.dam.kemon.service;

import com.damKemon.dam.kemon.model.ScrapingJob;
import com.damKemon.dam.kemon.repository.ScrapingJobRepository;
import com.damKemon.dam.kemon.scraper.ScrapedProduct;
import com.damKemon.dam.kemon.scraper.ScraperEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

@Service
public class ScrapingService {

    private static final Logger log = LoggerFactory.getLogger(ScrapingService.class);

    private final ScrapingJobRepository scrapingJobRepository;
    private final ScraperEngine scraperEngine;
    private final SearchService searchService;

    public ScrapingService(ScrapingJobRepository scrapingJobRepository,
                           ScraperEngine scraperEngine,
                           SearchService searchService) {
        this.scrapingJobRepository = scrapingJobRepository;
        this.scraperEngine = scraperEngine;
        this.searchService = searchService;
    }

    public ScrapingJob triggerScrape(String query, List<String> sites) {
        ScrapingJob job = ScrapingJob.builder()
                .query(query)
                .status("PENDING")
                .sitesRequested(sites != null && !sites.isEmpty() ? sites : getAllSiteSlugs())
                .sitesCompleted(new ArrayList<>())
                .startedAt(LocalDateTime.now())
                .build();

        ScrapingJob savedJob = scrapingJobRepository.save(job);

        // Run scraping asynchronously
        CompletableFuture.runAsync(() -> executeScrape(savedJob));

        return savedJob;
    }

    private void executeScrape(ScrapingJob job) {
        try {
            job.setStatus("RUNNING");
            scrapingJobRepository.save(job);

            searchService.search(job.getQuery());

            job.setStatus("COMPLETED");
            job.setSitesCompleted(job.getSitesRequested());
            job.setCompletedAt(LocalDateTime.now());
        } catch (Exception e) {
            log.error("Scraping job {} failed: {}", job.getId(), e.getMessage());
            job.setStatus("FAILED");
            job.setErrorMessage(e.getMessage());
            job.setCompletedAt(LocalDateTime.now());
        }
        scrapingJobRepository.save(job);
    }

    public Optional<ScrapingJob> getJob(String jobId) {
        return scrapingJobRepository.findById(jobId);
    }

    public List<ScrapingJob> getRecentJobs() {
        return scrapingJobRepository.findTop10ByOrderByStartedAtDesc();
    }

    private List<String> getAllSiteSlugs() {
        List<String> slugs = new ArrayList<>();
        scraperEngine.getScrapers().forEach(s -> slugs.add(s.getSiteSlug()));
        return slugs;
    }
}
