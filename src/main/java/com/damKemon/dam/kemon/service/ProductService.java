package com.damKemon.dam.kemon.service;

import com.damKemon.dam.kemon.model.PriceHistory;
import com.damKemon.dam.kemon.model.Product;
import com.damKemon.dam.kemon.model.Review;
import com.damKemon.dam.kemon.model.ShopTrust;
import com.damKemon.dam.kemon.repository.PriceHistoryRepository;
import com.damKemon.dam.kemon.repository.ProductRepository;
import com.damKemon.dam.kemon.repository.ReviewRepository;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class ProductService {

    private final ProductRepository productRepository;
    private final ReviewRepository reviewRepository;
    private final PriceHistoryRepository priceHistoryRepository;
    private final TrustService trustService;
    private final MongoTemplate mongoTemplate;

    public ProductService(ProductRepository productRepository,
                          ReviewRepository reviewRepository,
                          PriceHistoryRepository priceHistoryRepository,
                          TrustService trustService,
                          MongoTemplate mongoTemplate) {
        this.productRepository = productRepository;
        this.reviewRepository = reviewRepository;
        this.priceHistoryRepository = priceHistoryRepository;
        this.trustService = trustService;
        this.mongoTemplate = mongoTemplate;
    }

    /**
     * Distinct, non-blank product categories for the Browse filter, deduped
     * case-insensitively (the catalog has both "Smartphones" and "smartphones")
     * and sorted. Returns the lower-cased form; the UI capitalises for display.
     */
    public List<String> getCategories() {
        try {
            List<String> cats = mongoTemplate.findDistinct(new Query(), "category", Product.class, String.class);
            return cats.stream()
                    .filter(c -> c != null && !c.isBlank())
                    .map(c -> c.trim().toLowerCase())
                    .distinct()
                    .sorted()
                    .toList();
        } catch (DataAccessException e) {
            return Collections.emptyList();
        }
    }

    public Page<Product> getAllProducts(Pageable pageable) {
        return getAllProducts(null, pageable);
    }

    /** All products, or just one category when {@code category} is provided. */
    public Page<Product> getAllProducts(String category, Pageable pageable) {
        try {
            if (category != null && !category.isBlank()) {
                return productRepository.findByCategoryIgnoreCase(category.trim(), pageable);
            }
            return productRepository.findAll(pageable);
        } catch (DataAccessException e) {
            return new PageImpl<>(Collections.emptyList(), pageable, 0);
        }
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
        Optional<Product> p = findByIdOrSlug(productIdOrSlug);
        if (p.isEmpty()) return Collections.emptyList();
        try { return reviewRepository.findByProductIdOrderByReviewDateDesc(p.get().getId()); }
        catch (DataAccessException e) { return Collections.emptyList(); }
    }

    /** HTTP status + body pair so the controller stays a thin pass-through. */
    public record ReviewOutcome(int status, Object body) {}

    /**
     * Persist a shopper-submitted community review and fold its trust /
     * delivery / recommend signals into the seller's {@code ShopTrust}
     * profile. Anonymous: one review per browser ({@code anonId}) per
     * product. The product's own aggregate rating is intentionally left
     * untouched — those reflect scraped seller ratings; community sentiment
     * lives in the Reviews tab and the shop trust score.
     */
    public ReviewOutcome addCommunityReview(String idOrSlug, Map<String, Object> body, String anonHeader) {
        Optional<Product> po = findByIdOrSlug(idOrSlug);
        if (po.isEmpty()) return new ReviewOutcome(404, Map.of("error", "product not found"));
        Product product = po.get();

        Integer rating = asInt(body.get("rating"));
        if (rating == null || rating < 1 || rating > 5) {
            return new ReviewOutcome(400, Map.of("error", "rating (1..5) is required"));
        }

        String anonId = firstNonBlank(anonHeader, asStr(body.get("anonId")));
        String shopSlug = trimToNull(asStr(body.get("shopSlug")));
        String siteName = trimToNull(asStr(body.get("siteName")));
        String name = firstNonBlank(trimToNull(asStr(body.get("reviewerName"))), "Anonymous");
        String title = clamp(trimToNull(asStr(body.get("title"))), 140);
        String content = clamp(trimToNull(asStr(body.get("content"))), 2000);
        Integer deliveryDays = clampInt(asInt(body.get("deliveryDaysReported")), 0, 60);
        Boolean recommend = asBool(body.get("wouldRecommend"));
        String trustVote = normVote(asStr(body.get("trustVote")));

        if (anonId != null) {
            try {
                if (reviewRepository.countByProductIdAndAnonId(product.getId(), anonId) > 0) {
                    return new ReviewOutcome(409, Map.of("error", "you have already reviewed this product"));
                }
            } catch (DataAccessException ignored) { /* fall through; dedup is best-effort */ }
        }

        Review review = Review.builder()
                .productId(product.getId())
                .siteName(siteName)
                .shopSlug(shopSlug)
                .reviewerName(name)
                .rating(rating)
                .title(title)
                .content(content)
                .anonId(anonId)
                .deliveryDaysReported(deliveryDays)
                .wouldRecommend(recommend)
                .trustVote(trustVote)
                .helpfulCount(0)
                .source("community")
                .verified(false)
                .reviewDate(LocalDateTime.now())
                .build();
        try {
            review = reviewRepository.save(review);
        } catch (DataAccessException e) {
            return new ReviewOutcome(500, Map.of("error", "could not save review"));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("review", review);
        if (shopSlug != null) {
            ShopTrust updated = trustService.applyReview(shopSlug, rating, trustVote, recommend, deliveryDays);
            if (updated != null) out.put("trust", trustService.view(updated));
        }
        return new ReviewOutcome(201, out);
    }

    // ─── lenient body parsing (Jackson hands us Integer/Double/Boolean/String) ───

    private static Integer asInt(Object o) {
        if (o instanceof Number n) return n.intValue();
        if (o instanceof String s) { try { return Integer.valueOf(s.trim()); } catch (NumberFormatException e) { return null; } }
        return null;
    }

    private static Boolean asBool(Object o) {
        if (o instanceof Boolean b) return b;
        if (o instanceof String s) {
            String v = s.trim().toLowerCase();
            if (v.equals("true") || v.equals("yes") || v.equals("1")) return true;
            if (v.equals("false") || v.equals("no") || v.equals("0")) return false;
        }
        return null;
    }

    private static String asStr(Object o) { return o == null ? null : o.toString(); }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static String firstNonBlank(String a, String b) {
        String x = trimToNull(a);
        return x != null ? x : trimToNull(b);
    }

    private static String clamp(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static Integer clampInt(Integer v, int min, int max) {
        if (v == null) return null;
        return Math.max(min, Math.min(max, v));
    }

    private static String normVote(String s) {
        String v = trimToNull(s);
        if (v == null) return null;
        v = v.toLowerCase();
        return (v.equals("up") || v.equals("down")) ? v : null;
    }
}
