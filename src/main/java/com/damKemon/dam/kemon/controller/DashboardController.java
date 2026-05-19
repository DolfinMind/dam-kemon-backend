package com.damKemon.dam.kemon.controller;

import com.damKemon.dam.kemon.dto.DashboardStats;
import com.damKemon.dam.kemon.model.Product;
import com.damKemon.dam.kemon.model.ScrapingJob;
import com.damKemon.dam.kemon.model.Shop;
import com.damKemon.dam.kemon.repository.PriceHistoryRepository;
import com.damKemon.dam.kemon.repository.ProductRepository;
import com.damKemon.dam.kemon.repository.ReviewRepository;
import com.damKemon.dam.kemon.repository.ScrapingJobRepository;
import com.damKemon.dam.kemon.repository.ShopRepository;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Dashboard summary endpoint — reads the indexed catalog directly so the
 * numbers match what users see in search.
 */
@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final ProductRepository productRepository;
    private final ReviewRepository reviewRepository;
    private final PriceHistoryRepository priceHistoryRepository;
    private final ScrapingJobRepository scrapingJobRepository;
    private final ShopRepository shopRepository;

    public DashboardController(ProductRepository productRepository,
                               ReviewRepository reviewRepository,
                               PriceHistoryRepository priceHistoryRepository,
                               ScrapingJobRepository scrapingJobRepository,
                               ShopRepository shopRepository) {
        this.productRepository = productRepository;
        this.reviewRepository = reviewRepository;
        this.priceHistoryRepository = priceHistoryRepository;
        this.scrapingJobRepository = scrapingJobRepository;
        this.shopRepository = shopRepository;
    }

    @GetMapping("/stats")
    @Cacheable("dashboard-stats")
    public ResponseEntity<DashboardStats> getStats() {
        long totalProducts    = safe(() -> productRepository.count());
        long totalReviews     = safe(() -> reviewRepository.count());
        long totalPricePoints = safe(() -> priceHistoryRepository.count());

        List<String> recentSearches = safeList(() -> scrapingJobRepository.findTop10ByOrderByStartedAtDesc().stream()
                .map(ScrapingJob::getQuery).filter(Objects::nonNull).distinct().limit(5)
                .collect(Collectors.toList()));

        // Count cross-shop price points by walking the products once.
        Map<String, Long> productsBySite = new HashMap<>();
        try {
            for (Product p : productRepository.findAll()) {
                if (p.getPrices() == null) continue;
                Set<String> sites = new HashSet<>();
                p.getPrices().forEach(sp -> { if (sp.getSiteName() != null) sites.add(sp.getSiteName()); });
                sites.forEach(s -> productsBySite.merge(s, 1L, Long::sum));
            }
        } catch (DataAccessException ignored) {}

        // Show real shops from the catalog — sorted by productive first.
        List<DashboardStats.SiteStat> siteStats;
        try {
            List<Shop> shops = shopRepository.findAll();
            siteStats = shops.stream()
                    .map(s -> {
                        long count = productsBySite.getOrDefault(s.getName(), 0L);
                        String status = count > 0 ? "active"
                                : s.getLastError() != null ? "down"
                                : "dormant";
                        return DashboardStats.SiteStat.builder()
                                .siteName(s.getName())
                                .productCount(count)
                                .status(status)
                                .build();
                    })
                    .sorted((a, b) -> Long.compare(b.getProductCount(), a.getProductCount()))
                    .collect(Collectors.toList());
        } catch (DataAccessException e) {
            siteStats = Collections.emptyList();
        }

        long activeShops = siteStats.stream().filter(s -> "active".equals(s.getStatus())).count();

        DashboardStats stats = DashboardStats.builder()
                .totalProducts(totalProducts)
                .totalSites((int) activeShops)
                .totalReviews(totalReviews)
                .totalPricePoints(totalPricePoints)
                .recentSearches(recentSearches)
                .siteStats(siteStats)
                .build();
        return ResponseEntity.ok(stats);
    }

    private static long safe(java.util.function.LongSupplier s) {
        try { return s.getAsLong(); } catch (Exception e) { return 0L; }
    }
    private static <T> List<T> safeList(java.util.function.Supplier<List<T>> s) {
        try { return s.get(); } catch (Exception e) { return List.of(); }
    }
}
