package com.damKemon.dam.kemon.service;

import com.damKemon.dam.kemon.model.PriceHistory;
import com.damKemon.dam.kemon.model.Product;
import com.damKemon.dam.kemon.model.Review;
import com.damKemon.dam.kemon.repository.PriceHistoryRepository;
import com.damKemon.dam.kemon.repository.ProductRepository;
import com.damKemon.dam.kemon.repository.ReviewRepository;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.PageImpl;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

@Service
public class ProductService {

    private final ProductRepository productRepository;
    private final ReviewRepository reviewRepository;
    private final PriceHistoryRepository priceHistoryRepository;

    public ProductService(ProductRepository productRepository,
                          ReviewRepository reviewRepository,
                          PriceHistoryRepository priceHistoryRepository) {
        this.productRepository = productRepository;
        this.reviewRepository = reviewRepository;
        this.priceHistoryRepository = priceHistoryRepository;
    }

    public Page<Product> getAllProducts(Pageable pageable) {
        try { return productRepository.findAll(pageable); }
        catch (DataAccessException e) { return new PageImpl<>(Collections.emptyList(), pageable, 0); }
    }

    /** Bulk lookup preserving the caller's order. Missing ids are skipped. */
    public List<Product> findByIds(List<String> ids) {
        if (ids == null || ids.isEmpty()) return Collections.emptyList();
        try {
            Iterable<Product> rows = productRepository.findAllById(ids);
            java.util.Map<String, Product> byId = new java.util.HashMap<>();
            rows.forEach(p -> byId.put(p.getId(), p));
            List<Product> out = new java.util.ArrayList<>();
            for (String id : ids) {
                Product p = byId.get(id);
                if (p != null) out.add(p);
            }
            return out;
        } catch (DataAccessException e) {
            return Collections.emptyList();
        }
    }

    /**
     * Look up by Mongo {@code _id} first, then by {@code slug}. Returns
     * empty (not a 5xx) if MongoDB is unreachable, so the caller can show
     * a clean "not found" rather than "scraper unreachable".
     */
    public Optional<Product> findByIdOrSlug(String idOrSlug) {
        if (idOrSlug == null || idOrSlug.isBlank()) return Optional.empty();
        try {
            Optional<Product> byId = productRepository.findById(idOrSlug);
            if (byId.isPresent()) return byId;
        } catch (DataAccessException ignored) {}
        try {
            return productRepository.findBySlug(idOrSlug);
        } catch (DataAccessException e) {
            return Optional.empty();
        }
    }

    public List<PriceHistory> getPriceHistory(String productIdOrSlug) {
        try { return priceHistoryRepository.findByProductIdOrderByRecordedAtDesc(productIdOrSlug); }
        catch (DataAccessException e) { return Collections.emptyList(); }
    }

    /**
     * Daily-bucketed price series with missing days forward-filled from the
     * previous observation. Returns a stable, gap-free list of
     * {@code [{date: 'yyyy-MM-dd', price: Double}, …]} for the last N days
     * — exactly what the recharts line chart expects.
     */
    public List<java.util.Map<String, Object>> getDailyPriceSeries(String productIdOrSlug, int days) {
        if (days <= 0) days = 30;
        if (days > 365) days = 365;
        Optional<Product> p = findByIdOrSlug(productIdOrSlug);
        if (p.isEmpty()) return Collections.emptyList();
        String productId = p.get().getId();

        java.time.LocalDate today = java.time.LocalDate.now();
        java.time.LocalDate start = today.minusDays(days - 1L);

        java.util.TreeMap<java.time.LocalDate, Double> byDay = new java.util.TreeMap<>();
        try {
            for (PriceHistory h : priceHistoryRepository.findByProductIdOrderByRecordedAtDesc(productId)) {
                if (h.getRecordedAt() == null || h.getPrice() == null) continue;
                java.time.LocalDate d = h.getRecordedAt().toLocalDate();
                // Keep the earliest entry for each day (the order is desc, so we
                // overwrite — the final value for each day is the latest of that day).
                byDay.put(d, h.getPrice());
            }
        } catch (DataAccessException e) {
            return Collections.emptyList();
        }

        List<java.util.Map<String, Object>> series = new java.util.ArrayList<>();
        Double last = null;
        for (java.time.LocalDate d = start; !d.isAfter(today); d = d.plusDays(1)) {
            Double val = byDay.get(d);
            if (val != null) last = val;
            java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("date", d.toString());
            row.put("price", last);
            series.add(row);
        }
        // If there's no history but the product itself has a lowestPrice,
        // back-fill the entire window with that so the chart isn't empty.
        if (series.stream().allMatch(r -> r.get("price") == null) && p.get().getLowestPrice() != null) {
            double v = p.get().getLowestPrice();
            for (java.util.Map<String, Object> r : series) r.put("price", v);
        }
        return series;
    }

    public List<Review> getReviews(String productIdOrSlug) {
        try { return reviewRepository.findByProductId(productIdOrSlug); }
        catch (DataAccessException e) { return Collections.emptyList(); }
    }
}
