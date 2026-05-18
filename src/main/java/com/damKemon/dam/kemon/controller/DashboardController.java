package com.damKemon.dam.kemon.controller;

import com.damKemon.dam.kemon.dto.DashboardStats;
import com.damKemon.dam.kemon.model.Product;
import com.damKemon.dam.kemon.model.ScrapingJob;
import com.damKemon.dam.kemon.repository.PriceHistoryRepository;
import com.damKemon.dam.kemon.repository.ProductRepository;
import com.damKemon.dam.kemon.repository.ReviewRepository;
import com.damKemon.dam.kemon.repository.ScrapingJobRepository;
import com.damKemon.dam.kemon.scraper.ExtractorRegistry;
import com.damKemon.dam.kemon.scraper.ProductExtractor;
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
    private final ExtractorRegistry extractors;

    public DashboardController(ProductRepository productRepository,
                               ReviewRepository reviewRepository,
                               PriceHistoryRepository priceHistoryRepository,
                               ScrapingJobRepository scrapingJobRepository,
                               ExtractorRegistry extractors) {
        this.productRepository = productRepository;
        this.reviewRepository = reviewRepository;
        this.priceHistoryRepository = priceHistoryRepository;
        this.scrapingJobRepository = scrapingJobRepository;
        this.extractors = extractors;
    }

    @GetMapping("/stats")
    @Cacheable("dashboard-stats")
    public ResponseEntity<DashboardStats> getStats() {
        long totalProducts    = safeCount(productRepository::count);
        long totalReviews     = safeCount(reviewRepository::count);
        long totalPricePoints = safeCount(priceHistoryRepository::count);

        List<String> recentSearches;
        try {
            recentSearches = scrapingJobRepository.findTop10ByOrderByStartedAtDesc().stream()
                    .map(ScrapingJob::getQuery).filter(Objects::nonNull).distinct().limit(5)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            recentSearches = List.of();
        }

        Map<String, Long> productsBySite = new HashMap<>();
        try {
            for (Product p : productRepository.findAll()) {
                if (p.getPrices() == null) continue;
                Set<String> sites = new HashSet<>();
                p.getPrices().forEach(sp -> { if (sp.getSiteName() != null) sites.add(sp.getSiteName()); });
                sites.forEach(s -> productsBySite.merge(s, 1L, Long::sum));
            }
        } catch (Exception ignored) {}

        // One row per known site-specific extractor + one synthetic "Generic / other" row
        // summarising any DDG-discovered hosts.
        Set<String> knownNames = extractors.knownSiteNames();
        List<DashboardStats.SiteStat> siteStats = new ArrayList<>();
        for (ProductExtractor e : extractors.all()) {
            if ("generic".equals(e.getSiteSlug())) continue;
            siteStats.add(DashboardStats.SiteStat.builder()
                    .siteName(e.getSiteName())
                    .productCount(productsBySite.getOrDefault(e.getSiteName(), 0L))
                    .status("active").build());
        }
        long otherProducts = productsBySite.entrySet().stream()
                .filter(en -> !knownNames.contains(en.getKey()))
                .mapToLong(Map.Entry::getValue).sum();
        if (otherProducts > 0) {
            siteStats.add(DashboardStats.SiteStat.builder()
                    .siteName("Other (DDG-discovered)")
                    .productCount(otherProducts)
                    .status("active").build());
        }

        DashboardStats stats = DashboardStats.builder()
                .totalProducts(totalProducts)
                .totalSites(siteStats.size())
                .totalReviews(totalReviews)
                .totalPricePoints(totalPricePoints)
                .recentSearches(recentSearches)
                .siteStats(siteStats)
                .build();
        return ResponseEntity.ok(stats);
    }

    private static long safeCount(java.util.function.LongSupplier supplier) {
        try { return supplier.getAsLong(); } catch (Exception e) { return 0L; }
    }
}
