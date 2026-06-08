package com.damKemon.dam.kemon.controller;

import com.damKemon.dam.kemon.indexer.BulkIndexer;
import com.damKemon.dam.kemon.indexer.ShopDiscoveryService;
import com.damKemon.dam.kemon.service.HotDropsService;
import com.damKemon.dam.kemon.service.SellerDirectoryService;
import com.damKemon.dam.kemon.service.SyntheticMonitorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Background-jobs operator panel. Lists every @Scheduled job we run + their
 * known cadence, exposes a run-now trigger for each, and tracks last N runs
 * in memory so operators can confirm a kick-off without waiting for the
 * next scheduled fire.
 */
@RestController
@RequestMapping("/api/admin/jobs")
public class AdminJobsController {

    private static final Logger log = LoggerFactory.getLogger(AdminJobsController.class);

    private final BulkIndexer indexer;
    private final ShopDiscoveryService discovery;
    private final HotDropsService hotDrops;
    private final SyntheticMonitorService synthetic;
    private final SellerDirectoryService sellerDirectory;

    /** id → ring-buffer of last N run timestamps (manual only). */
    private final ConcurrentHashMap<String, java.util.Deque<Map<String, Object>>> recent =
            new ConcurrentHashMap<>();

    public AdminJobsController(BulkIndexer indexer,
                               ShopDiscoveryService discovery,
                               HotDropsService hotDrops,
                               SyntheticMonitorService synthetic,
                               SellerDirectoryService sellerDirectory) {
        this.indexer = indexer;
        this.discovery = discovery;
        this.hotDrops = hotDrops;
        this.synthetic = synthetic;
        this.sellerDirectory = sellerDirectory;
    }

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> list() {
        return ResponseEntity.ok(List.of(
                jobRow("indexer-nightly", "Nightly full indexer", "0 0 3 * * *",
                        "Crawls every active shop + writes products"),
                jobRow("indexer-retry", "Retry pass", "0 0 4 * * *",
                        "Re-runs shops that returned 0 or errored last night"),
                jobRow("price-history-snapshot", "Daily price snapshot", "0 0 4 * * *",
                        "Writes per-shop current price into price_history"),
                jobRow("hot-drops-rebuild", "Hot-drops rebuild", "0 0 5 * * *",
                        "Recomputes products with ≥10% drop vs 7d peak"),
                jobRow("shop-discovery", "Shop discovery", "manual",
                        "Walks e-cab + BASIS, queues new shops into pending_shops"),
                jobRow("seller-sync", "Seller directory sync", "0 30 5 * * *",
                        "Upserts active shops + marketplace storefronts into the sellers directory"),
                jobRow("daraz-deep", "Daraz deep harvest", "manual",
                        "Re-harvests Daraz only with deep paging — more distinct sellers per product"),
                jobRow("revive-tech", "Revive dormant tech shops", "manual",
                        "Re-crawls dormant tech/mobile shops (run with browser on) to add sellers per product"),
                jobRow("synthetic-monitor", "Synthetic search canary", "every 15 min",
                        "Runs sample queries; flips /actuator/health/synthetic")
        ));
    }

    @PostMapping("/{id}/run")
    public ResponseEntity<Map<String, Object>> runNow(@PathVariable String id) {
        CompletableFuture.runAsync(() -> {
            try {
                switch (id) {
                    case "indexer-nightly" -> indexer.runAll();
                    case "indexer-retry" -> indexer.runRetry();
                    case "shop-discovery" -> discovery.discover();
                    case "seller-sync" -> sellerDirectory.syncOnce();
                    case "daraz-deep" -> indexer.runOne("daraz");
                    case "revive-tech" -> indexer.runShops(java.util.List.of(
                            // mobile first (phone depth), then computing/accessories
                            "sumashtech", "mobilebuzzbd", "mobilezonebd", "gadgetnova", "priyoshop",
                            "computervillage", "skyland", "ultratech", "ittechbd", "techbangla",
                            "dhakatechbd", "toolsterminal", "miniso", "pcbuilderbd", "earphonebd",
                            "smartzone", "ekshop", "singerbd", "sindabad", "robishop", "techcity"));
                    case "hot-drops-rebuild" -> hotDrops.rebuild();
                    case "synthetic-monitor" -> synthetic.run();
                    default -> {
                        log.warn("AdminJobs: unknown job '{}'", id);
                        return;
                    }
                }
                recordRun(id, true, null);
            } catch (Exception e) {
                log.warn("AdminJobs: job '{}' threw: {}", id, e.getMessage());
                recordRun(id, false, e.getMessage());
            }
        });
        return ResponseEntity.accepted().body(Map.of("started", true, "id", id));
    }

    @GetMapping("/{id}/runs")
    public ResponseEntity<List<Map<String, Object>>> runs(@PathVariable String id) {
        java.util.Deque<Map<String, Object>> q = recent.get(id);
        return ResponseEntity.ok(q == null ? List.of() : new java.util.ArrayList<>(q));
    }

    private Map<String, Object> jobRow(String id, String name, String cadence, String description) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("name", name);
        row.put("cadence", cadence);
        row.put("description", description);
        java.util.Deque<Map<String, Object>> r = recent.get(id);
        row.put("lastRuns", r == null ? List.of() : new java.util.ArrayList<>(r));
        return row;
    }

    private void recordRun(String id, boolean ok, String error) {
        java.util.Deque<Map<String, Object>> q = recent.computeIfAbsent(id,
                k -> new java.util.concurrent.ConcurrentLinkedDeque<>());
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("ts", Instant.now().toString());
        entry.put("ok", ok);
        if (error != null) entry.put("error", error);
        q.addFirst(entry);
        while (q.size() > 10) q.removeLast();
    }
}
