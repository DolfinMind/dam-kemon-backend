package com.damKemon.dam.kemon.controller;

import com.damKemon.dam.kemon.model.PriceHistory;
import com.damKemon.dam.kemon.model.Product;
import com.damKemon.dam.kemon.model.Review;
import com.damKemon.dam.kemon.model.SitePrice;
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
    public ResponseEntity<Product> getProductById(@PathVariable String idOrSlug, HttpServletRequest req) {
        return productService.findByIdOrSlug(idOrSlug)
                .map(p -> anon(req) ? gateForAnonymous(p) : p)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /** Member feature — anonymous callers get 401 and the UI shows the signup gate. */
    @GetMapping("/{idOrSlug}/history")
    public ResponseEntity<List<PriceHistory>> getPriceHistory(@PathVariable String idOrSlug, HttpServletRequest req) {
        if (anon(req)) return ResponseEntity.status(401).build();
        return ResponseEntity.ok(productService.getPriceHistory(idOrSlug));
    }

    /**
     * Gap-free daily-bucketed price series, ready to drop into a recharts
     * line chart. {@code days} bounded to 1..365. Member feature, like /history.
     */
    @GetMapping("/{idOrSlug}/history/daily")
    public ResponseEntity<List<java.util.Map<String, Object>>> getDailyPriceHistory(
            @PathVariable String idOrSlug,
            @RequestParam(value = "days", defaultValue = "30") int days,
            HttpServletRequest req) {
        if (anon(req)) return ResponseEntity.status(401).build();
        return ResponseEntity.ok(productService.getDailyPriceSeries(idOrSlug, days));
    }

    @GetMapping("/{idOrSlug}/reviews")
    public ResponseEntity<List<Review>> getReviews(@PathVariable String idOrSlug, HttpServletRequest req) {
        List<Review> all = productService.getReviews(idOrSlug);
        // ponytail: anon teaser = first 3, matching the UI's initialVisible.
        // The true count travels in a header so the gate can say "all N reviews".
        List<Review> body = anon(req) && all.size() > 3 ? List.copyOf(all.subList(0, 3)) : all;
        return ResponseEntity.ok()
                .header("X-Total-Reviews", String.valueOf(all.size()))
                .body(body);
    }

    /** Signed-in state, as stamped by JwtAuthFilter. */
    private static boolean anon(HttpServletRequest req) {
        return req.getAttribute("authUserId") == null;
    }

    private static final int ANON_VISIBLE_OFFERS = 4;

    /**
     * Signed-out teaser of the offer list: the four cheapest distinct sellers,
     * with the single cheapest offer's identity stripped — its price stays
     * visible (and true, so SEO titles/rich results stay honest), but WHICH
     * shop sells at it is the signup carrot. {@code totalSellerCount} carries
     * the real distinct-seller count for the "12 shops" header.
     * Safe to trim in place: findByIdOrSlug is uncached, the entity is
     * request-local and never saved on this path.
     * ponytail: list/search payloads still carry full prices[] for anonymous
     * callers — strip there too if bulk scraping of those becomes real.
     */
    private static Product gateForAnonymous(Product p) {
        List<SitePrice> all = p.getPrices() == null ? List.of() : p.getPrices();
        // Cheapest first, one row per seller — mirrors the UI's dedupe key.
        List<SitePrice> distinct = new java.util.ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        all.stream()
                .sorted(java.util.Comparator.comparing(SitePrice::getPrice,
                        java.util.Comparator.nullsLast(Double::compareTo)))
                .forEach(sp -> {
                    String key = sp.getSellerId() != null ? sp.getSellerId()
                            : (sp.getSiteSlug() != null ? sp.getSiteSlug() : String.valueOf(sp.getSiteName()))
                              + "|" + (sp.getSellerName() == null ? "" : sp.getSellerName());
                    if (seen.add(key)) distinct.add(sp);
                });
        p.setTotalSellerCount(distinct.size());
        List<SitePrice> visible = new java.util.ArrayList<>(
                distinct.subList(0, Math.min(ANON_VISIBLE_OFFERS, distinct.size())));
        if (!visible.isEmpty() && visible.get(0).getPrice() != null) {
            SitePrice best = visible.get(0);
            visible.set(0, SitePrice.builder()
                    .price(best.getPrice())
                    .originalPrice(best.getOriginalPrice())
                    .currency(best.getCurrency())
                    .inStock(best.getInStock())
                    .rating(best.getRating())
                    .reviewCount(best.getReviewCount())
                    .soldCount(best.getSoldCount())
                    .locked(true)
                    .build());
        }
        p.setPrices(visible);
        return p;
    }

    /**
     * Submit a community review. Signed-in identity makes reputation and
     * one-review-per-product enforcement meaningful. Body: {@code rating} (1..5, required),
     * plus optional {@code title, content, reviewerName, shopSlug, siteName,
     * deliveryDaysReported, wouldRecommend, trustVote}.
     */
    @PostMapping("/{idOrSlug}/reviews")
    public ResponseEntity<Object> addReview(@PathVariable String idOrSlug,
                                            @RequestBody(required = false) Map<String, Object> body,
                                            HttpServletRequest req) {
        String userId = (String) req.getAttribute("authUserId");
        if (userId == null) return ResponseEntity.status(401).body(Map.of("error", "sign in to review"));
        ProductService.ReviewOutcome outcome = productService.addCommunityReview(
                idOrSlug, body == null ? Map.of() : body, userId, req.getHeader("X-Anon-Id"));
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
