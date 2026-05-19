package com.damKemon.dam.kemon.controller;

import com.damKemon.dam.kemon.indexer.BulkIndexer;
import com.damKemon.dam.kemon.indexer.BulkIndexer.RunSummary;
import com.damKemon.dam.kemon.model.PendingShop;
import com.damKemon.dam.kemon.model.Shop;
import com.damKemon.dam.kemon.repository.PendingShopRepository;
import com.damKemon.dam.kemon.repository.ProductRepository;
import com.damKemon.dam.kemon.repository.ShopRepository;
import com.damKemon.dam.kemon.service.HotDropsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Operator-only endpoints, all behind {@code X-Admin-Key}. The public
 * dashboard reads only safe aggregates from {@code /api/dashboard/stats}.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);

    private final BulkIndexer indexer;
    private final ShopRepository shopRepository;
    private final ProductRepository productRepository;
    private final PendingShopRepository pendingShopRepository;
    private final HotDropsService hotDrops;

    public AdminController(BulkIndexer indexer,
                           ShopRepository shopRepository,
                           ProductRepository productRepository,
                           PendingShopRepository pendingShopRepository,
                           HotDropsService hotDrops) {
        this.indexer = indexer;
        this.shopRepository = shopRepository;
        this.productRepository = productRepository;
        this.pendingShopRepository = pendingShopRepository;
        this.hotDrops = hotDrops;
    }

    @PostMapping("/index/run")
    public ResponseEntity<Map<String, Object>> kickoff(
            @RequestParam(value = "wipe", defaultValue = "false") boolean wipe) {
        if (wipe) {
            try {
                long before = productRepository.count();
                productRepository.deleteAll();
                log.info("AdminController: wiped {} products before reindex", before);
            } catch (Exception e) {
                log.warn("AdminController: wipe failed: {}", e.getMessage());
            }
        }
        CompletableFuture.runAsync(() -> {
            try { indexer.runAll(); }
            catch (Exception e) { log.error("Manual indexer run crashed", e); }
        });
        return ResponseEntity.accepted().body(Map.of(
                "started", true,
                "wiped", wipe,
                "message", "Indexer running in background. Poll /api/admin/index/status."
        ));
    }

    @PostMapping("/index/retry")
    public ResponseEntity<Map<String, Object>> retry() {
        CompletableFuture.runAsync(() -> {
            try { indexer.runRetry(); }
            catch (Exception e) { log.error("Manual retry crashed", e); }
        });
        return ResponseEntity.accepted().body(Map.of(
                "started", true,
                "message", "Retry pass running in background."
        ));
    }

    @PostMapping("/index/shop/{slug}")
    public ResponseEntity<Map<String, Object>> reindexOne(@PathVariable String slug) {
        CompletableFuture.runAsync(() -> {
            try { indexer.runOne(slug); }
            catch (Exception e) { log.error("Reindex of {} crashed", slug, e); }
        });
        return ResponseEntity.accepted().body(Map.of(
                "started", true,
                "shop", slug
        ));
    }

    @GetMapping("/index/status")
    public ResponseEntity<RunSummary> status() {
        return ResponseEntity.ok(indexer.getLastRun());
    }

    @GetMapping("/shops")
    public ResponseEntity<List<Map<String, Object>>> listShops() {
        try {
            List<Shop> shops = shopRepository.findAll();
            List<Map<String, Object>> out = shops.stream().map(s -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("slug", s.getSlug());
                m.put("name", s.getName());
                m.put("baseUrl", s.getBaseUrl());
                m.put("platform", s.getPlatform());
                m.put("categories", s.getCategories());
                m.put("status", s.getStatus());
                m.put("health", s.getHealth());
                m.put("consecutiveFailures", s.getConsecutiveFailures());
                m.put("needsRetry", s.getNeedsRetry());
                m.put("sitemapUrl", s.getSitemapUrl());
                m.put("lastIndexedAt", s.getLastIndexedAt());
                m.put("lastIndexedCount", s.getLastIndexedCount());
                m.put("lastError", s.getLastError());
                m.put("recentRuns", s.getRecentRuns());
                return m;
            }).toList();
            return ResponseEntity.ok(out);
        } catch (DataAccessException e) {
            return ResponseEntity.ok(Collections.emptyList());
        }
    }

    @PostMapping("/shops/{slug}/status")
    public ResponseEntity<Map<String, Object>> setShopStatus(
            @PathVariable String slug,
            @RequestBody Map<String, String> body) {
        String newStatus = body.get("status");
        if (newStatus == null || !List.of("active", "blocked", "dormant", "draft").contains(newStatus)) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    "status must be one of active|blocked|dormant|draft"));
        }
        try {
            Shop s = shopRepository.findBySlug(slug).orElse(null);
            if (s == null) return ResponseEntity.notFound().build();
            s.setStatus(newStatus);
            if ("active".equals(newStatus)) s.setConsecutiveFailures(0);
            shopRepository.save(s);
            return ResponseEntity.ok(Map.of("slug", slug, "status", newStatus));
        } catch (DataAccessException e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    // ───── Pending-shop submissions (Phase 2 "Submit your shop") ─────

    @GetMapping("/pending-shops")
    public ResponseEntity<List<PendingShop>> listPending() {
        try { return ResponseEntity.ok(pendingShopRepository.findAll()); }
        catch (DataAccessException e) { return ResponseEntity.ok(List.of()); }
    }

    @PostMapping("/pending-shops/{id}/approve")
    public ResponseEntity<Map<String, Object>> approvePending(@PathVariable String id) {
        try {
            PendingShop p = pendingShopRepository.findById(id).orElse(null);
            if (p == null) return ResponseEntity.notFound().build();

            String slug = slugify(p.getName() == null ? p.getBaseUrl() : p.getName());
            if (shopRepository.findBySlug(slug).isPresent()) {
                return ResponseEntity.status(409).body(Map.of("error", "shop already exists", "slug", slug));
            }
            Shop s = Shop.builder()
                    .slug(slug)
                    .name(p.getName())
                    .baseUrl(p.getBaseUrl())
                    .sitemapUrl(p.getSitemapUrl())
                    .platform(p.getPlatform())
                    .categories(p.getCategories() == null ? new ArrayList<>() : p.getCategories())
                    .status("active")
                    .health("active")
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build();
            shopRepository.save(s);

            p.setStatus("approved");
            p.setReviewedAt(LocalDateTime.now());
            pendingShopRepository.save(p);

            return ResponseEntity.ok(Map.of("approved", true, "slug", slug));
        } catch (DataAccessException e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/pending-shops/{id}/reject")
    public ResponseEntity<Map<String, Object>> rejectPending(@PathVariable String id,
                                                             @RequestBody(required = false) Map<String, String> body) {
        try {
            PendingShop p = pendingShopRepository.findById(id).orElse(null);
            if (p == null) return ResponseEntity.notFound().build();
            p.setStatus("rejected");
            p.setReviewedAt(LocalDateTime.now());
            if (body != null) p.setReviewNote(body.get("note"));
            pendingShopRepository.save(p);
            return ResponseEntity.ok(Map.of("rejected", true));
        } catch (DataAccessException e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/hot-drops/rebuild")
    public ResponseEntity<Map<String, Object>> rebuildHotDrops() {
        CompletableFuture.runAsync(() -> {
            try { hotDrops.rebuild(); }
            catch (Exception e) { log.error("Hot-drops rebuild crashed", e); }
        });
        return ResponseEntity.accepted().body(Map.of("started", true));
    }

    private static String slugify(String name) {
        return name.toLowerCase().replaceAll("[^a-z0-9\\s-]", "")
                .replaceAll("\\s+", "-").replaceAll("-+", "-")
                .replaceAll("^-|-$", "");
    }
}
