package com.damKemon.dam.kemon.controller;

import com.damKemon.dam.kemon.model.Product;
import com.damKemon.dam.kemon.model.Shop;
import com.damKemon.dam.kemon.repository.ProductRepository;
import com.damKemon.dam.kemon.repository.ShopRepository;
import com.damKemon.dam.kemon.service.AnalyticsService;
import com.damKemon.dam.kemon.service.AdminAnalyticsService;
import com.damKemon.dam.kemon.util.ClientIp;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Public, fire-and-forget analytics ingestion. Each endpoint
 * accepts a small JSON body, writes the event asynchronously, and returns
 * 204 immediately so the client (often a {@code sendBeacon}) doesn't block.
 *
 * <p>No-PII payload contract: an anon UUID ties guest events together and the
 * auth filter can attach a user ID to signed-in events. The server hashes the
 * IP and discards the raw value.
 */
@RestController
@RequestMapping("/api/events")
public class AnalyticsController {

    private static final Set<String> ACTION_TYPES = Set.of(
            "member_intent_save", "member_intent_track", "alert_target_set", "auth_success",
            "member_action_completed_save", "member_action_completed_track",
            "saved_search_created");

    private final AnalyticsService analytics;
    private final AdminAnalyticsService adminAnalytics;
    private final ProductRepository products;
    private final ShopRepository shops;

    public AnalyticsController(AnalyticsService analytics, AdminAnalyticsService adminAnalytics,
                               ProductRepository products, ShopRepository shops) {
        this.analytics = analytics;
        this.adminAnalytics = adminAnalytics;
        this.products = products;
        this.shops = shops;
    }

    @PostMapping("/view")
    public ResponseEntity<Void> view(@RequestBody Map<String, Object> body,
                                     HttpServletRequest req) {
        analytics.recordView(asString(body.get("productId")), asString(body.get("anonId")), ClientIp.of(req),
                asString(req.getAttribute("authUserId")), req.getHeader("User-Agent"));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/click")
    public ResponseEntity<Void> click(@RequestBody Map<String, Object> body,
                                      HttpServletRequest req) {
        analytics.recordClick(
                asString(body.get("productId")),
                asString(body.get("sellerSlug")),
                asString(body.get("anonId")),
                ClientIp.of(req),
                asString(req.getAttribute("authUserId")),
                req.getHeader("User-Agent"));
        return ResponseEntity.noContent().build();
    }

    /** Autosuggest dropdown click — records "typed X, picked product Y". */
    @PostMapping("/suggest-click")
    public ResponseEntity<Void> suggestClick(@RequestBody Map<String, Object> body,
                                             HttpServletRequest req) {
        analytics.recordSuggestClick(
                asString(body.get("query")),
                asString(body.get("productId")),
                asString(body.get("productName")),
                asString(body.get("anonId")),
                ClientIp.of(req),
                asString(req.getAttribute("authUserId")),
                req.getHeader("User-Agent"));
        return ResponseEntity.noContent().build();
    }

    /**
     * A single-page-app route change. The client fires this on every navigation
     * so the server sees the full page-by-page journey, not just API calls.
     */
    @PostMapping("/pageview")
    public ResponseEntity<Void> pageview(@RequestBody Map<String, Object> body,
                                         HttpServletRequest req) {
        String userId = (String) req.getAttribute("authUserId");
        analytics.recordPageView(
                asString(body.get("path")),
                asString(body.get("anonId")),
                ClientIp.of(req),
                userId,
                asString(body.get("referer")),
                req.getHeader("User-Agent"));
        return ResponseEntity.noContent().build();
    }

    /** Small, bounded conversion events that connect anonymous intent to member value. */
    @PostMapping("/action")
    public ResponseEntity<Void> action(@RequestBody Map<String, Object> body,
                                       HttpServletRequest req) {
        String type = asString(body.get("type"));
        if (!ACTION_TYPES.contains(type)) return ResponseEntity.badRequest().build();
        analytics.recordAction(type, asString(body.get("productId")),
                asString(body.get("anonId")), ClientIp.of(req),
                asString(req.getAttribute("authUserId")), req.getHeader("User-Agent"));
        return ResponseEntity.noContent().build();
    }

    /**
     * Public endpoint to get trending/hot products based on outbound clicks,
     * enriched with catalog data (slug, image, lowest price) so the /trending
     * page can render full product cards.
     */
    @GetMapping("/trending")
    public ResponseEntity<List<Map<String, Object>>> trending() {
        // ponytail: widen 1d→7d→30d until the page fills; per-request Mongo hit, cache if traffic hurts
        List<Map<String, Object>> rows = List.of();
        for (int days : new int[]{1, 7, 30}) {
            rows = adminAnalytics.topClickedProducts(days, 24);
            if (rows.size() >= 6) break;
        }
        Map<String, Product> byId = new HashMap<>();
        products.findAllById(rows.stream().map(r -> (String) r.get("productId")).toList())
                .forEach(p -> byId.put(p.getId(), p));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Product p = byId.get((String) r.get("productId"));
            if (p == null) continue; // product left the catalog — don't link to a 404
            r.put("slug", p.getSlug());
            r.put("imageUrl", p.getImageUrl());
            r.put("lowestPrice", p.getLowestPrice());
            if (p.getName() != null) r.put("name", p.getName());
            out.add(r);
        }
        return ResponseEntity.ok(out);
    }

    /** Public: shops ranked by real outbound clicks, for the /trending page. */
    @GetMapping("/trending-shops")
    public ResponseEntity<List<Map<String, Object>>> trendingShops() {
        List<Map<String, Object>> rows = List.of();
        for (int days : new int[]{1, 7, 30}) {
            rows = adminAnalytics.topShops(days, 10);
            if (rows.size() >= 5) break;
        }
        Map<String, String> names = new HashMap<>();
        for (Shop s : shops.findAll()) {
            if (s.getSlug() != null && s.getName() != null) names.put(s.getSlug(), s.getName());
        }
        for (Map<String, Object> r : rows) {
            String slug = (String) r.get("siteSlug");
            r.put("name", names.getOrDefault(slug, slug));
        }
        return ResponseEntity.ok(rows);
    }

    private static String asString(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
