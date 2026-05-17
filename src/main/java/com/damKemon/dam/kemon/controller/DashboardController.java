package com.damKemon.dam.kemon.controller;

import com.damKemon.dam.kemon.dto.DashboardStats;
import com.damKemon.dam.kemon.model.Product;
import com.damKemon.dam.kemon.model.ScrapingJob;
import com.damKemon.dam.kemon.repository.PriceHistoryRepository;
import com.damKemon.dam.kemon.repository.ProductRepository;
import com.damKemon.dam.kemon.repository.ReviewRepository;
import com.damKemon.dam.kemon.repository.ScrapingJobRepository;
import com.damKemon.dam.kemon.scraper.EcommerceScraper;
import com.damKemon.dam.kemon.scraper.ScraperEngine;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final ProductRepository productRepository;
    private final ReviewRepository reviewRepository;
    private final PriceHistoryRepository priceHistoryRepository;
    private final ScrapingJobRepository scrapingJobRepository;
    private final ScraperEngine scraperEngine;

    public DashboardController(ProductRepository productRepository,
                               ReviewRepository reviewRepository,
                               PriceHistoryRepository priceHistoryRepository,
                               ScrapingJobRepository scrapingJobRepository,
                               ScraperEngine scraperEngine) {
        this.productRepository = productRepository;
        this.reviewRepository = reviewRepository;
        this.priceHistoryRepository = priceHistoryRepository;
        this.scrapingJobRepository = scrapingJobRepository;
        this.scraperEngine = scraperEngine;
    }

    /**
     * Cached for ~2 minutes. The previous implementation called
     * productRepository.findAll() once per scraper (N+1 over the entire
     * collection), which on Atlas was ~10s. Now we do one findAll() and
     * compute everything in-memory.
     */
    @GetMapping("/stats")
    @Cacheable("dashboard-stats")
    public ResponseEntity<DashboardStats> getStats() {
        long totalProducts    = productRepository.count();
        long totalReviews     = reviewRepository.count();
        long totalPricePoints = priceHistoryRepository.count();

        // Recent unique queries from scrape jobs
        List<String> recentSearches = scrapingJobRepository.findTop10ByOrderByStartedAtDesc()
                .stream()
                .map(ScrapingJob::getQuery)
                .filter(Objects::nonNull)
                .distinct()
                .limit(5)
                .collect(Collectors.toList());

        // ONE collection scan, then bucket by site in memory
        Map<String, Long> productsBySite = new HashMap<>();
        for (Product p : productRepository.findAll()) {
            if (p.getPrices() == null) continue;
            Set<String> sites = new HashSet<>();
            p.getPrices().forEach(sp -> { if (sp.getSiteName() != null) sites.add(sp.getSiteName()); });
            sites.forEach(s -> productsBySite.merge(s, 1L, Long::sum));
        }

        List<DashboardStats.SiteStat> siteStats = scraperEngine.getScrapers().stream()
                .map(s -> DashboardStats.SiteStat.builder()
                        .siteName(s.getSiteName())
                        .productCount(productsBySite.getOrDefault(s.getSiteName(), 0L))
                        .status("active")
                        .build())
                .collect(Collectors.toList());

        DashboardStats stats = DashboardStats.builder()
                .totalProducts(totalProducts)
                .totalSites(scraperEngine.getScrapers().size())
                .totalReviews(totalReviews)
                .totalPricePoints(totalPricePoints)
                .recentSearches(recentSearches)
                .siteStats(siteStats)
                .build();

        return ResponseEntity.ok(stats);
    }
}
