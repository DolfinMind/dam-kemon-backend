package com.damKemon.dam.kemon.controller;

import com.damKemon.dam.kemon.model.PriceHistory;
import com.damKemon.dam.kemon.model.Product;
import com.damKemon.dam.kemon.model.Review;
import com.damKemon.dam.kemon.service.ProductService;
import com.damKemon.dam.kemon.service.ShowcaseService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final ProductService productService;
    private final ShowcaseService showcaseService;

    public ProductController(ProductService productService, ShowcaseService showcaseService) {
        this.productService = productService;
        this.showcaseService = showcaseService;
    }

    @GetMapping
    public ResponseEntity<Page<Product>> getAllProducts(
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size,
            @RequestParam(value = "category", required = false) String category) {
        Pageable pageable = PageRequest.of(page, size);
        return ResponseEntity.ok(productService.getAllProducts(category, pageable));
    }

    /** Distinct categories for the Browse filter chips. */
    @GetMapping("/categories")
    public ResponseEntity<List<String>> getCategories() {
        return ResponseEntity.ok(productService.getCategories());
    }

    /** Homepage rail: products offered by the most sellers (max side-by-side value). */
    @GetMapping("/most-sellers")
    public ResponseEntity<List<Product>> mostSellers(
            @RequestParam(value = "limit", defaultValue = "10") int limit,
            @RequestParam(value = "minSellers", defaultValue = "2") int minSellers) {
        return ResponseEntity.ok(productService.mostSellers(Math.max(1, Math.min(limit, 24)), minSellers));
    }

    /** Homepage rails, PRECOMPUTED in ShowcaseService — a volatile read, so
     *  the landing page never waits on category queries. */
    @GetMapping("/showcase")
    public ResponseEntity<List<Map<String, Object>>> showcase(
            @RequestParam(value = "perCategory", defaultValue = "6") int perCategory) {
        return ResponseEntity.ok(showcaseService.get(Math.max(1, Math.min(perCategory, 6))));
    }

    /**
     * Bulk hydration for the "recently viewed" rail. Client passes the
     * IDs it remembers in localStorage, we return only those that still
     * exist. Order is preserved.
     */
    @GetMapping("/by-ids")
    public ResponseEntity<List<Product>> getByIds(@RequestParam("ids") String ids) {
        if (ids == null || ids.isBlank()) return ResponseEntity.ok(List.of());
        List<String> idList = java.util.Arrays.stream(ids.split(","))
                .map(String::trim).filter(s -> !s.isEmpty())
                .limit(50)
                .toList();
        return ResponseEntity.ok(productService.findByIds(idList));
    }

    /** Accepts either a Mongo {@code _id} or a {@code slug}. */
    @GetMapping("/{idOrSlug}")
    public ResponseEntity<Product> getProductById(@PathVariable String idOrSlug) {
        return productService.findByIdOrSlug(idOrSlug)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{idOrSlug}/history")
    public ResponseEntity<List<PriceHistory>> getPriceHistory(@PathVariable String idOrSlug) {
        return ResponseEntity.ok(productService.getPriceHistory(idOrSlug));
    }

    /**
     * Gap-free daily-bucketed price series, ready to drop into a recharts
     * line chart. {@code days} bounded to 1..365.
     */
    @GetMapping("/{idOrSlug}/history/daily")
    public ResponseEntity<List<java.util.Map<String, Object>>> getDailyPriceHistory(
            @PathVariable String idOrSlug,
            @RequestParam(value = "days", defaultValue = "30") int days) {
        return ResponseEntity.ok(productService.getDailyPriceSeries(idOrSlug, days));
    }

    @GetMapping("/{idOrSlug}/reviews")
    public ResponseEntity<List<Review>> getReviews(@PathVariable String idOrSlug) {
        return ResponseEntity.ok(productService.getReviews(idOrSlug));
    }

    /**
     * Submit a community review. Anonymous — identity is the {@code X-Anon-Id}
     * browser id (one review per product). Body: {@code rating} (1..5, required),
     * plus optional {@code title, content, reviewerName, shopSlug, siteName,
     * deliveryDaysReported, wouldRecommend, trustVote}.
     */
    @PostMapping("/{idOrSlug}/reviews")
    public ResponseEntity<Object> addReview(@PathVariable String idOrSlug,
                                            @RequestBody(required = false) Map<String, Object> body,
                                            HttpServletRequest req) {
        ProductService.ReviewOutcome outcome = productService.addCommunityReview(
                idOrSlug, body == null ? Map.of() : body, req.getHeader("X-Anon-Id"));
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * Lightweight delivery-time report (no full review). Body:
     * {@code shopSlug} (required), {@code days} (0..60, required). Anonymous
     * via {@code X-Anon-Id}; one report per product per browser.
     */
    @PostMapping("/{idOrSlug}/delivery-report")
    public ResponseEntity<Object> deliveryReport(@PathVariable String idOrSlug,
                                                 @RequestBody(required = false) Map<String, Object> body,
                                                 HttpServletRequest req) {
        ProductService.ReviewOutcome outcome = productService.addDeliveryReport(
                idOrSlug, body == null ? Map.of() : body, req.getHeader("X-Anon-Id"));
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }
}
