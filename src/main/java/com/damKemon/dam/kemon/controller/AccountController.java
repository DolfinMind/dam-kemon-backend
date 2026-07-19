package com.damKemon.dam.kemon.controller;

import com.damKemon.dam.kemon.model.AnalyticsEvent;
import com.damKemon.dam.kemon.model.PriceAlertNotification;
import com.damKemon.dam.kemon.model.Product;
import com.damKemon.dam.kemon.model.SavedSearch;
import com.damKemon.dam.kemon.model.WishlistItem;
import com.damKemon.dam.kemon.repository.AnalyticsEventRepository;
import com.damKemon.dam.kemon.repository.PriceAlertNotificationRepository;
import com.damKemon.dam.kemon.repository.ProductRepository;
import com.damKemon.dam.kemon.repository.SavedSearchRepository;
import com.damKemon.dam.kemon.repository.WishlistItemRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Per-user data: saved searches, wishlist. Every endpoint reads {@code
 * authUserId} from the request, populated by {@code JwtAuthFilter}.
 * Unauthenticated callers get 401.
 */
@RestController
@RequestMapping("/api/account")
public class AccountController {

    private final SavedSearchRepository savedSearches;
    private final WishlistItemRepository wishlist;
    private final ProductRepository products;
    private final AnalyticsEventRepository events;
    private final PriceAlertNotificationRepository notifications;

    public AccountController(SavedSearchRepository savedSearches,
                             WishlistItemRepository wishlist,
                             ProductRepository products,
                             AnalyticsEventRepository events,
                             PriceAlertNotificationRepository notifications) {
        this.savedSearches = savedSearches;
        this.wishlist = wishlist;
        this.products = products;
        this.events = events;
        this.notifications = notifications;
    }

    @GetMapping("/search-history")
    public ResponseEntity<?> searchHistory(HttpServletRequest req) {
        String userId = requireUserId(req);
        if (userId == null) return unauthorised();
        try {
            Instant month = Instant.now().minus(30, ChronoUnit.DAYS);
            List<Map<String, Object>> out = new ArrayList<>();
            for (AnalyticsEvent e : events.findByTypeAndTsAfter("search", month)) {
                if (!userId.equals(e.getUserId())) continue;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("query", e.getQuery());
                row.put("resultCount", e.getResultCount());
                row.put("ts", e.getTs());
                out.add(row);
            }
            out.sort((a, b) -> ((Instant) b.get("ts")).compareTo((Instant) a.get("ts")));
            return ResponseEntity.ok(out.size() > 50 ? out.subList(0, 50) : out);
        } catch (DataAccessException e) {
            return ResponseEntity.ok(List.of());
        }
    }

    @GetMapping("/saved-searches")
    public ResponseEntity<?> listSavedSearches(HttpServletRequest req) {
        String userId = requireUserId(req);
        if (userId == null) return unauthorised();
        try { return ResponseEntity.ok(savedSearches.findByUserId(userId)); }
        catch (DataAccessException e) { return ResponseEntity.ok(List.of()); }
    }

    @PostMapping("/saved-searches")
    public ResponseEntity<?> addSavedSearch(@RequestBody Map<String, String> body,
                                            HttpServletRequest req) {
        String userId = requireUserId(req);
        if (userId == null) return unauthorised();
        String email = (String) req.getAttribute("authUserEmail");

        String query = body == null ? null : body.get("query");
        if (query == null || query.trim().length() < 2) {
            return ResponseEntity.badRequest().body(Map.of("error", "query must be at least 2 chars"));
        }
        SavedSearch s = SavedSearch.builder()
                .userId(userId)
                .query(query.trim())
                .notifyEmail(body.getOrDefault("notifyEmail", email))
                .createdAt(LocalDateTime.now())
                .build();
        try { return ResponseEntity.ok(savedSearches.save(s)); }
        catch (DataAccessException e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "could not save"));
        }
    }

    @DeleteMapping("/saved-searches/{id}")
    public ResponseEntity<?> deleteSavedSearch(@PathVariable String id, HttpServletRequest req) {
        String userId = requireUserId(req);
        if (userId == null) return unauthorised();
        try {
            SavedSearch s = savedSearches.findById(id).orElse(null);
            if (s == null) return ResponseEntity.notFound().build();
            if (!userId.equals(s.getUserId())) return ResponseEntity.status(403).body(Map.of("error", "forbidden"));
            savedSearches.deleteById(id);
            return ResponseEntity.noContent().build();
        } catch (DataAccessException e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "could not delete"));
        }
    }

    @GetMapping("/wishlist")
    public ResponseEntity<?> listWishlist(HttpServletRequest req) {
        String userId = requireUserId(req);
        if (userId == null) return unauthorised();
        try {
            List<WishlistItem> items = wishlist.findByUserId(userId);
            // Hydrate with product data so the frontend renders without a 2nd round-trip
            List<Map<String, Object>> out = new ArrayList<>();
            for (WishlistItem w : items) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", w.getId());
                row.put("addedAt", w.getAddedAt());
                row.put("priceAtAdd", w.getPriceAtAdd());
                row.put("targetPrice", w.getTargetPrice());
                row.put("alertOnDropPercent", w.getAlertOnDropPercent());
                row.put("notifyChannel", w.getNotifyChannel());
                row.put("alertsEnabled", Boolean.TRUE.equals(w.getAlertsEnabled()));
                row.put("lastNotifiedAt", w.getLastNotifiedAt());
                Product p = products.findById(w.getProductId()).orElse(null);
                if (p != null) {
                    Map<String, Object> pmap = new LinkedHashMap<>();
                    pmap.put("id", p.getId());
                    pmap.put("slug", p.getSlug());
                    pmap.put("name", p.getName());
                    pmap.put("imageUrl", p.getImageUrl());
                    pmap.put("category", p.getCategory());
                    pmap.put("lowestPrice", p.getLowestPrice());
                    pmap.put("sellerCount", p.getPrices() == null ? 0 : p.getPrices().size());
                    row.put("product", pmap);
                } else {
                    row.put("productId", w.getProductId());
                    row.put("missing", true);
                }
                out.add(row);
            }
            return ResponseEntity.ok(out);
        } catch (DataAccessException e) {
            return ResponseEntity.ok(List.of());
        }
    }

    /**
     * Update the alert settings for a wishlist item. Body is a partial JSON
     * patch — any of: {@code targetPrice}, {@code alertOnDropPercent},
     * {@code notifyChannel}, {@code alertsEnabled}. Missing keys leave the
     * existing value untouched.
     */
    @PatchMapping("/wishlist/{productId}")
    public ResponseEntity<?> updateWishlistAlert(@PathVariable String productId,
                                                 @RequestBody Map<String, Object> body,
                                                 HttpServletRequest req) {
        String userId = requireUserId(req);
        if (userId == null) return unauthorised();
        try {
            WishlistItem w = wishlist.findByUserIdAndProductId(userId, productId).orElse(null);
            if (w == null) return ResponseEntity.notFound().build();

            if (body.containsKey("targetPrice"))
                w.setTargetPrice(asDouble(body.get("targetPrice")));
            if (body.containsKey("alertOnDropPercent"))
                w.setAlertOnDropPercent(asDouble(body.get("alertOnDropPercent")));
            if (body.containsKey("notifyChannel"))
                w.setNotifyChannel(asString(body.get("notifyChannel")));
            if (body.containsKey("alertsEnabled"))
                w.setAlertsEnabled(Boolean.TRUE.equals(body.get("alertsEnabled")));

            return ResponseEntity.ok(wishlist.save(w));
        } catch (DataAccessException e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "could not update"));
        }
    }

    /** In-app notification feed for the bell dropdown. Most recent first. */
    @GetMapping("/notifications")
    public ResponseEntity<?> listNotifications(@RequestParam(value = "limit", defaultValue = "20") int limit,
                                               HttpServletRequest req) {
        String userId = requireUserId(req);
        if (userId == null) return unauthorised();
        try {
            int capped = Math.max(1, Math.min(limit, 100));
            List<PriceAlertNotification> rows =
                    notifications.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, capped));
            long unread = notifications.countByUserIdAndUnreadTrue(userId);
            return ResponseEntity.ok(Map.of("items", rows, "unread", unread));
        } catch (DataAccessException e) {
            return ResponseEntity.ok(Map.of("items", List.of(), "unread", 0));
        }
    }

    @PostMapping("/notifications/read")
    public ResponseEntity<?> markAllRead(HttpServletRequest req) {
        String userId = requireUserId(req);
        if (userId == null) return unauthorised();
        try {
            // Lazy implementation: read + update each. Volumes are tiny (per-user list).
            List<PriceAlertNotification> rows =
                    notifications.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, 200));
            for (PriceAlertNotification n : rows) {
                if (Boolean.TRUE.equals(n.getUnread())) {
                    n.setUnread(false);
                    notifications.save(n);
                }
            }
            return ResponseEntity.noContent().build();
        } catch (DataAccessException e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "could not update"));
        }
    }

    private static Double asDouble(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(v.toString()); }
        catch (NumberFormatException e) { return null; }
    }

    private static String asString(Object v) {
        return v == null ? null : v.toString();
    }

    @PostMapping("/wishlist")
    public ResponseEntity<?> addToWishlist(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        String userId = requireUserId(req);
        if (userId == null) return unauthorised();
        String productId = body == null ? null : asString(body.get("productId"));
        if (productId == null || productId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "productId required"));
        }
        try {
            Optional<WishlistItem> existing = wishlist.findByUserIdAndProductId(userId, productId);
            if (existing.isPresent()) {
                WishlistItem item = existing.get();
                if (Boolean.TRUE.equals(body.get("alertsEnabled")) && !Boolean.TRUE.equals(item.getAlertsEnabled())) {
                    item.setAlertsEnabled(true);
                    item = wishlist.save(item);
                }
                return ResponseEntity.ok(item);
            }

            Product p = products.findById(productId).orElse(null);
            WishlistItem w = WishlistItem.builder()
                    .userId(userId)
                    .productId(productId)
                    .priceAtAdd(p == null ? null : p.getLowestPrice())
                    .alertsEnabled(Boolean.TRUE.equals(body.get("alertsEnabled")))
                    .notifyChannel("email")
                    .addedAt(LocalDateTime.now())
                    .build();
            return ResponseEntity.ok(wishlist.save(w));
        } catch (DataAccessException e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "could not save"));
        }
    }

    @DeleteMapping("/wishlist/{productId}")
    public ResponseEntity<?> removeFromWishlist(@PathVariable String productId, HttpServletRequest req) {
        String userId = requireUserId(req);
        if (userId == null) return unauthorised();
        try {
            wishlist.deleteByUserIdAndProductId(userId, productId);
            return ResponseEntity.noContent().build();
        } catch (DataAccessException e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "could not delete"));
        }
    }

    private static String requireUserId(HttpServletRequest req) {
        Object id = req.getAttribute("authUserId");
        return id instanceof String ? (String) id : null;
    }

    private static ResponseEntity<Map<String, Object>> unauthorised() {
        return ResponseEntity.status(401).body(Map.of("error", "sign in to use this"));
    }
}
