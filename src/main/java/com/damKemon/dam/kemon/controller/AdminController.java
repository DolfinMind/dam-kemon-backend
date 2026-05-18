package com.damKemon.dam.kemon.controller;

import com.damKemon.dam.kemon.indexer.BulkIndexer;
import com.damKemon.dam.kemon.indexer.BulkIndexer.RunSummary;
import com.damKemon.dam.kemon.model.Shop;
import com.damKemon.dam.kemon.repository.ProductRepository;
import com.damKemon.dam.kemon.repository.ShopRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Admin endpoints for triggering the indexer + inspecting catalog state.
 *
 * <p>{@code POST /api/admin/index/run} kicks off a full crawl in the
 * background and returns immediately (the run takes minutes-to-hours).
 * Poll {@code GET /api/admin/index/status} to watch progress.
 *
 * <p>{@code GET /api/admin/shops} lists every shop with its last-indexed
 * stats — useful when triaging "why are there no results for X".
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);

    private final BulkIndexer indexer;
    private final ShopRepository shopRepository;
    private final ProductRepository productRepository;

    public AdminController(BulkIndexer indexer,
                           ShopRepository shopRepository,
                           ProductRepository productRepository) {
        this.indexer = indexer;
        this.shopRepository = shopRepository;
        this.productRepository = productRepository;
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
                m.put("sitemapUrl", s.getSitemapUrl());
                m.put("lastIndexedAt", s.getLastIndexedAt());
                m.put("lastIndexedCount", s.getLastIndexedCount());
                m.put("lastError", s.getLastError());
                return m;
            }).toList();
            return ResponseEntity.ok(out);
        } catch (DataAccessException e) {
            return ResponseEntity.ok(Collections.emptyList());
        }
    }
}
