package com.damKemon.dam.kemon.controller;

import com.damKemon.dam.kemon.indexer.BulkIndexer;
import com.damKemon.dam.kemon.service.OperatorStatsService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Admin-only analytics readback. Behind {@code /api/admin/*} so it
 * inherits the JWT/X-Admin-Key gate.
 */
@RestController
@RequestMapping("/api/admin/stats")
public class OperatorStatsController {

    private final OperatorStatsService stats;
    private final BulkIndexer indexer;

    public OperatorStatsController(OperatorStatsService stats, BulkIndexer indexer) {
        this.stats = stats;
        this.indexer = indexer;
    }

    @GetMapping("/overview")
    public ResponseEntity<Map<String, Object>> overview() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("users", stats.userCounts());
        out.put("lastIndexerRun", indexer.getLastRun());
        return ResponseEntity.ok(out);
    }

    @GetMapping("/zero-results")
    public ResponseEntity<List<Map<String, Object>>> zeroResults(
            @RequestParam(value = "limit", defaultValue = "20") int limit) {
        return ResponseEntity.ok(stats.zeroResultSearches(Math.max(1, Math.min(limit, 100))));
    }

    @GetMapping("/shop-ctr")
    public ResponseEntity<List<Map<String, Object>>> shopCtr(
            @RequestParam(value = "limit", defaultValue = "20") int limit) {
        return ResponseEntity.ok(stats.ctrPerShop(Math.max(1, Math.min(limit, 100))));
    }

    @GetMapping("/top-products")
    public ResponseEntity<Map<String, Object>> topProducts(
            @RequestParam(value = "limit", defaultValue = "10") int limit) {
        return ResponseEntity.ok(stats.topProducts(Math.max(1, Math.min(limit, 50))));
    }

    @GetMapping("/latency")
    public ResponseEntity<Map<String, Object>> latency() {
        return ResponseEntity.ok(stats.searchLatency());
    }

    @GetMapping("/recent-searches")
    public ResponseEntity<List<Map<String, Object>>> recentSearches(
            @RequestParam(value = "limit", defaultValue = "200") int limit) {
        return ResponseEntity.ok(stats.recentSearches(Math.max(1, Math.min(limit, 1000))));
    }
}
