package com.damKemon.dam.kemon.controller;

import com.damKemon.dam.kemon.config.AppRole;
import com.damKemon.dam.kemon.indexer.BulkIndexer;
import com.damKemon.dam.kemon.indexer.BulkIndexer.RunSummary;
import com.damKemon.dam.kemon.indexer.ScraperLearningService;
import com.damKemon.dam.kemon.indexer.ShopDiscoveryService;
import com.damKemon.dam.kemon.model.ShopDiagnostic;
import com.damKemon.dam.kemon.repository.ShopDiagnosticRepository;
import com.damKemon.dam.kemon.model.AuditLogEntry;
import com.damKemon.dam.kemon.model.IndexerRunRecord;
import com.damKemon.dam.kemon.model.PendingShop;
import com.damKemon.dam.kemon.model.Shop;
import com.damKemon.dam.kemon.repository.AuditLogRepository;
import com.damKemon.dam.kemon.repository.IndexerRunRepository;
import com.damKemon.dam.kemon.repository.PendingShopRepository;
import com.damKemon.dam.kemon.repository.ProductRepository;
import com.damKemon.dam.kemon.repository.ShopRepository;
import com.damKemon.dam.kemon.service.HotDropsService;
import com.damKemon.dam.kemon.service.MarketplaceSellerService;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
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
    private final ShopDiscoveryService discovery;
    private final AuditLogRepository auditRepo;
    private final IndexerRunRepository indexerRunRepo;
    private final ScraperLearningService learner;
    private final ShopDiagnosticRepository diagnosticRepo;
    private final MarketplaceSellerService sellerService;
    private final AppRole appRole;
    private final org.springframework.data.mongodb.core.MongoTemplate mongoTemplate;

    public AdminController(BulkIndexer indexer,
                           ShopRepository shopRepository,
                           ProductRepository productRepository,
                           PendingShopRepository pendingShopRepository,
                           HotDropsService hotDrops,
                           ShopDiscoveryService discovery,
                           AuditLogRepository auditRepo,
                           IndexerRunRepository indexerRunRepo,
                           ScraperLearningService learner,
                           ShopDiagnosticRepository diagnosticRepo,
                           MarketplaceSellerService sellerService,
                           AppRole appRole,
                           org.springframework.data.mongodb.core.MongoTemplate mongoTemplate) {
        this.indexer = indexer;
        this.shopRepository = shopRepository;
        this.productRepository = productRepository;
        this.pendingShopRepository = pendingShopRepository;
        this.hotDrops = hotDrops;
        this.discovery = discovery;
        this.auditRepo = auditRepo;
        this.indexerRunRepo = indexerRunRepo;
        this.learner = learner;
        this.diagnosticRepo = diagnosticRepo;
        this.sellerService = sellerService;
        this.appRole = appRole;
        this.mongoTemplate = mongoTemplate;
    }

    /** Heavy crawl triggers are refused on the API ("web") node — they run on the
     *  worker (damkemon-prod-worker.service) so a crawl can never spike the
     *  request-serving JVM. Returns 409 with the one-liner to start the worker. */
    private ResponseEntity<Map<String, Object>> crawlOnWorkerOnly() {
        return ResponseEntity.status(409).body(Map.of(
                "error", "crawl_disabled_on_api",
                "message", "Crawling runs on the worker, not the API node. "
                        + "Start it with: sudo systemctl start damkemon-prod-worker.service"));
    }

    /** Recompute marketplace per-seller reputation from the current catalog. */
    @PostMapping("/recompute-seller-trust")
    public ResponseEntity<Map<String, Object>> recomputeSellerTrust() {
        int n = sellerService.recompute();
        return ResponseEntity.ok(Map.of("recomputed", n));
    }

    @GetMapping("/index/history")
    public ResponseEntity<List<IndexerRunRecord>> indexHistory(
            @RequestParam(value = "limit", defaultValue = "30") int limit) {
        try {
            return ResponseEntity.ok(indexerRunRepo.findAllByOrderByStartedAtDesc(
                    PageRequest.of(0, Math.max(1, Math.min(limit, 200)))));
        } catch (org.springframework.dao.DataAccessException e) {
            return ResponseEntity.ok(List.of());
        }
    }

    @GetMapping("/audit-log")
    public ResponseEntity<List<AuditLogEntry>> auditLog(
            @RequestParam(value = "limit", defaultValue = "200") int limit) {
        try {
            return ResponseEntity.ok(auditRepo.findAllByOrderByTsDesc(
                    PageRequest.of(0, Math.max(1, Math.min(limit, 1000)))));
        } catch (org.springframework.dao.DataAccessException e) {
            return ResponseEntity.ok(List.of());
        }
    }

    @PostMapping("/discover-shops")
    public ResponseEntity<Map<String, Object>> discoverShops() {
        if (!appRole.isWorker()) return crawlOnWorkerOnly();
        return ResponseEntity.ok(discovery.discover());
    }

    /**
     * Latest diagnostic from the auto-learning service for a single shop.
     * Returns 404 if the learner hasn't run on this shop yet (typically
     * because it hasn't been at 0 products for a full cron cycle).
     */
    @GetMapping("/shops/{slug}/diagnostic")
    public ResponseEntity<?> shopDiagnostic(@PathVariable String slug) {
        try {
            return diagnosticRepo.findTopByShopSlugOrderByTsDesc(slug)
                    .<ResponseEntity<?>>map(ResponseEntity::ok)
                    .orElseGet(() -> ResponseEntity.status(404).body(Map.of(
                            "error", "no_diagnostic",
                            "message", "Learner hasn't run a probe on this shop yet — trigger one with POST /api/admin/shops/" + slug + "/learn"
                    )));
        } catch (DataAccessException e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Wipe every shop's learned {@code preferredExtractor} +
     * {@code lastLearnedAt}. Use after fixing a learner bug so the next
     * cron starts from scratch — otherwise shops stay locked to whatever
     * the buggy version decided. Returns the count cleared.
     */
    @PostMapping("/shops/clear-learned")
    public ResponseEntity<?> clearLearnedExtractors() {
        try {
            int cleared = 0;
            for (Shop s : shopRepository.findAll()) {
                if (s.getPreferredExtractor() != null || s.getLastLearnedAt() != null) {
                    s.setPreferredExtractor(null);
                    s.setLastLearnedAt(null);
                    s.setUpdatedAt(LocalDateTime.now());
                    shopRepository.save(s);
                    cleared++;
                }
            }
            return ResponseEntity.ok(Map.of("cleared", cleared));
        } catch (DataAccessException e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Force a learning probe right now — useful for testing extractors
     * without waiting for the next 0-product cron run. Synchronous so the
     * response is the diagnostic.
     */
    @PostMapping("/shops/{slug}/learn")
    public ResponseEntity<?> learnShop(@PathVariable String slug) {
        try {
            Shop shop = shopRepository.findBySlug(slug).orElse(null);
            if (shop == null) return ResponseEntity.notFound().build();
            // Bypass the 24h throttle for manual triggers.
            shop.setLastLearnedAt(null);
            ShopDiagnostic d = learner.learnFromBrokenShop(shop);
            if (d == null) return ResponseEntity.ok(Map.of("status", "skipped",
                    "message", "Learner is disabled (set learner.enabled=true)."));
            return ResponseEntity.ok(d);
        } catch (DataAccessException e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Concise "what's broken" report for the operator dashboard. Returns
     * three buckets: shops that successfully indexed products, shops with
     * zero products last run, and shops that failed entirely. Cheap to
     * compute — just walks the shops collection in memory.
     */
    @GetMapping("/shops/health")
    public ResponseEntity<Map<String, Object>> shopsHealth() {
        try {
            List<Shop> all = shopRepository.findAll();
            List<Map<String, Object>> healthy = new ArrayList<>();
            List<Map<String, Object>> zeroProducts = new ArrayList<>();
            List<Map<String, Object>> failing = new ArrayList<>();
            for (Shop s : all) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("slug", s.getSlug());
                row.put("name", s.getName());
                row.put("baseUrl", s.getBaseUrl());
                row.put("platform", s.getPlatform());
                row.put("requiresJs", s.getRequiresJs());
                row.put("lastIndexedAt", s.getLastIndexedAt());
                row.put("lastIndexedCount", s.getLastIndexedCount());
                row.put("lastError", s.getLastError());
                row.put("consecutiveFailures", s.getConsecutiveFailures());
                int count = s.getLastIndexedCount() == null ? 0 : s.getLastIndexedCount();
                String err = s.getLastError();
                if (err != null && !err.isBlank()) failing.add(row);
                else if (count == 0) zeroProducts.add(row);
                else healthy.add(row);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("healthy", healthy);
            out.put("zeroProducts", zeroProducts);
            out.put("failing", failing);
            out.put("counts", Map.of(
                    "healthy", healthy.size(),
                    "zeroProducts", zeroProducts.size(),
                    "failing", failing.size(),
                    "total", all.size()
            ));
            return ResponseEntity.ok(out);
        } catch (DataAccessException e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/index/run")
    public ResponseEntity<Map<String, Object>> kickoff(
            @RequestParam(value = "wipe", defaultValue = "false") boolean wipe) {
        if (!appRole.isWorker()) return crawlOnWorkerOnly();
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
        if (!appRole.isWorker()) return crawlOnWorkerOnly();
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
        if (!appRole.isWorker()) return crawlOnWorkerOnly();
        CompletableFuture.runAsync(() -> {
            try { indexer.runOne(slug); }
            catch (Exception e) { log.error("Reindex of {} crashed", slug, e); }
        });
        return ResponseEntity.accepted().body(Map.of(
                "started", true,
                "shop", slug
        ));
    }

    /**
     * Live indexer status, cross-process. The crawl runs in the WORKER JVM;
     * this (web) JVM's in-memory RunSummary is empty unless the run happened
     * here. So: serve in-memory when it has data, else the worker's heartbeat
     * doc from Mongo. A heartbeat stuck "inProgress" for >10 min is reported
     * stalled (worker crashed mid-run). Always includes the live catalog size.
     */
    @GetMapping("/index/status")
    public ResponseEntity<Map<String, Object>> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        RunSummary mem = indexer.getLastRun();
        if (mem.startedAtEpochMs > 0) {
            out.put("startedAtEpochMs", mem.startedAtEpochMs);
            out.put("finishedAtEpochMs", mem.finishedAtEpochMs);
            out.put("shopsAttempted", mem.shopsAttempted);
            out.put("shopsSucceeded", mem.shopsSucceeded);
            out.put("shopsFailed", mem.shopsFailed);
            out.put("productsInserted", mem.productsInserted);
            out.put("productsMerged", mem.productsMerged);
            out.put("urlsScraped", mem.urlsScraped);
            out.put("inProgress", mem.inProgress);
            out.put("source", "this-jvm");
        } else {
            try {
                org.bson.Document live = mongoTemplate.getCollection("indexer_live")
                        .find(new org.bson.Document("_id", "live")).first();
                if (live != null) {
                    for (String k : List.of("kind", "currentShop", "startedAtEpochMs", "finishedAtEpochMs",
                            "shopsAttempted", "shopsSucceeded", "shopsFailed", "productsInserted",
                            "productsMerged", "urlsScraped", "inProgress", "heartbeatMs")) {
                        if (live.get(k) != null) out.put(k, live.get(k));
                    }
                    long beat = live.get("heartbeatMs") instanceof Number n ? n.longValue() : 0;
                    boolean running = Boolean.TRUE.equals(live.getBoolean("inProgress"));
                    if (running && System.currentTimeMillis() - beat > 10 * 60_000L) {
                        out.put("inProgress", false);
                        out.put("stalled", true);
                    }
                    out.put("source", "worker");
                }
            } catch (DataAccessException ignored) { /* no live doc — fine */ }
        }
        try { out.put("catalogSize", productRepository.count()); } catch (DataAccessException ignored) {}
        return ResponseEntity.ok(out);
    }

    private final java.util.concurrent.atomic.AtomicLong countsRefreshedAt =
            new java.util.concurrent.atomic.AtomicLong(0);

    /**
     * Recompute every shop's {@code catalogCount} (distinct products currently
     * carrying that shop's offer) and denormalise it onto the shop docs, so the
     * admin table can page/sort by the TRUE count. {@code lastIndexedCount}
     * only reflects the last crawl — feed-sync, manual ingest and remerges all
     * drift from it, which is why the Products column read wrong.
     * ponytail: refreshed lazily on admin loads with a 10-min TTL (~1s pass
     * over the catalog); move to a scheduler if admins ever feel the first hit.
     */
    private void refreshCatalogCountsIfStale() {
        long now = System.currentTimeMillis();
        long last = countsRefreshedAt.get();
        if (now - last < 10 * 60_000L || !countsRefreshedAt.compareAndSet(last, now)) return;
        try {
            List<org.bson.Document> pipeline = List.of(
                    new org.bson.Document("$project", new org.bson.Document("slugs",
                            new org.bson.Document("$setUnion", List.of(
                                    new org.bson.Document("$ifNull", List.of("$prices.siteSlug", List.of())),
                                    List.of())))),
                    new org.bson.Document("$unwind", "$slugs"),
                    new org.bson.Document("$group", new org.bson.Document("_id", "$slugs")
                            .append("n", new org.bson.Document("$sum", 1))));
            Map<String, Integer> counts = new LinkedHashMap<>();
            mongoTemplate.getCollection("products").aggregate(pipeline).allowDiskUse(true)
                    .forEach(d -> counts.put(String.valueOf(d.get("_id")), ((Number) d.get("n")).intValue()));
            // Zero first (separate call, so ordering vs the unordered bulk is safe):
            // a shop whose products all merged away must not keep a stale count.
            mongoTemplate.updateMulti(new Query(),
                    new org.springframework.data.mongodb.core.query.Update().set("catalogCount", 0), Shop.class);
            if (!counts.isEmpty()) {
                var bulk = mongoTemplate.bulkOps(
                        org.springframework.data.mongodb.core.BulkOperations.BulkMode.UNORDERED, Shop.class);
                for (Map.Entry<String, Integer> e : counts.entrySet()) {
                    bulk.updateMulti(new Query(Criteria.where("slug").is(e.getKey())),
                            new org.springframework.data.mongodb.core.query.Update().set("catalogCount", e.getValue()));
                }
                bulk.execute();
            }
        } catch (Exception e) {
            log.warn("catalog-count refresh failed: {}", e.getMessage());
        }
    }

    /**
     * Shop list. Without {@code page} it keeps the legacy full-array shape
     * (growth scripts parse that); with {@code page} it returns a Mongo-side
     * paginated {@code {shops, page, size, totalElements}} so the admin table
     * never pulls the whole collection. {@code recentRuns} is dropped from both
     * shapes — nothing reads it and it was the bulk of the old payload.
     */
    @GetMapping("/shops")
    public ResponseEntity<?> listShops(
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", defaultValue = "100") int size,
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "health", required = false) String health,
            @RequestParam(value = "sort", defaultValue = "name") String sort) {
        try {
            if (page == null) {
                return ResponseEntity.ok(shopRepository.findAll().stream()
                        .map(AdminController::shopRow).toList());
            }
            refreshCatalogCountsIfStale();
            Query query = new Query();
            if (q != null && !q.isBlank()) {
                String rx = java.util.regex.Pattern.quote(q.trim());
                query.addCriteria(new Criteria().orOperator(
                        Criteria.where("name").regex(rx, "i"),
                        Criteria.where("slug").regex(rx, "i")));
            }
            if (health != null && !health.isBlank() && !"all".equals(health)) {
                if ("failing".equals(health)) {
                    query.addCriteria(new Criteria().orOperator(
                            Criteria.where("consecutiveFailures").gt(0),
                            Criteria.where("needsRetry").is(true)));
                } else if ("active".equals(health)) {
                    // legacy rows have no health field — they count as active
                    query.addCriteria(new Criteria().orOperator(
                            Criteria.where("health").is("active"),
                            Criteria.where("health").is(null)));
                } else {
                    query.addCriteria(Criteria.where("health").is(health));
                }
            }
            long total = mongoTemplate.count(query, Shop.class);
            Sort order = "products".equals(sort)
                    ? Sort.by(Sort.Direction.DESC, "catalogCount")
                    : Sort.by(Sort.Direction.ASC, "name");
            query.with(PageRequest.of(Math.max(0, page), Math.max(1, Math.min(size, 500)), order));
            query.fields().exclude("recentRuns");
            List<Map<String, Object>> out = mongoTemplate.find(query, Shop.class).stream()
                    .map(AdminController::shopRow).toList();
            return ResponseEntity.ok(Map.of(
                    "shops", out, "page", page, "size", size, "totalElements", total));
        } catch (DataAccessException e) {
            return ResponseEntity.ok(page == null ? Collections.emptyList()
                    : Map.of("shops", List.of(), "totalElements", 0L));
        }
    }

    private static Map<String, Object> shopRow(Shop s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("slug", s.getSlug());
        m.put("name", s.getName());
        m.put("baseUrl", s.getBaseUrl());
        m.put("platform", s.getPlatform());
        m.put("feedUrl", s.getFeedUrl());
        m.put("categories", s.getCategories());
        m.put("status", s.getStatus());
        m.put("blockedBy", s.getBlockedBy());
        m.put("health", s.getHealth());
        m.put("consecutiveFailures", s.getConsecutiveFailures());
        m.put("needsRetry", s.getNeedsRetry());
        m.put("sitemapUrl", s.getSitemapUrl());
        m.put("requiresJs", s.getRequiresJs());
        m.put("lastIndexedAt", s.getLastIndexedAt());
        m.put("lastIndexedCount", s.getLastIndexedCount());
        m.put("catalogCount", s.getCatalogCount());
        m.put("lastError", s.getLastError());
        return m;
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
            // findAllBySlug, not findBySlug: a boot-race could have duplicated this
            // slug, and findBySlug THROWS on duplicates — the hide button would 500
            // on exactly the shop the operator most wants gone. Update every match.
            List<Shop> matches = shopRepository.findAllBySlug(slug);
            if (matches.isEmpty()) return ResponseEntity.notFound().build();
            for (Shop s : matches) {
                s.setStatus(newStatus);
                // Operator intent is sticky: the lifecycle reviver only undoes "auto" blocks.
                s.setBlockedBy("active".equals(newStatus) ? null : "operator");
                if ("active".equals(newStatus)) s.setConsecutiveFailures(0);
                shopRepository.save(s);
            }
            // Homepage rail prices come from a prebuilt set — recompute it now so
            // hiding a shop cleans the rail in seconds, not at the next 4h cron.
            CompletableFuture.runAsync(hotDrops::rebuild);
            return ResponseEntity.ok(Map.of("slug", slug, "status", newStatus, "updated", matches.size()));
        } catch (DataAccessException e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Edit shop metadata in place. Operators use this to fix a wrong sitemap URL,
     * toggle the {@code requiresJs} flag, or tweak categories without redeploying.
     */
    @PatchMapping("/shops/{slug}")
    public ResponseEntity<?> editShop(@PathVariable String slug, @RequestBody Map<String, Object> body) {
        try {
            Shop s = shopRepository.findAllBySlug(slug).stream().findFirst().orElse(null);
            if (s == null) return ResponseEntity.notFound().build();
            if (body.containsKey("name")) s.setName(String.valueOf(body.get("name")));
            if (body.containsKey("baseUrl")) s.setBaseUrl(String.valueOf(body.get("baseUrl")));
            if (body.containsKey("sitemapUrl")) {
                Object v = body.get("sitemapUrl");
                s.setSitemapUrl(v == null || String.valueOf(v).isBlank() ? null : String.valueOf(v));
            }
            if (body.containsKey("feedUrl")) {
                Object v = body.get("feedUrl");
                s.setFeedUrl(v == null || String.valueOf(v).isBlank() ? null : String.valueOf(v));
            }
            if (body.containsKey("platform")) s.setPlatform(String.valueOf(body.get("platform")));
            if (body.containsKey("requiresJs")) s.setRequiresJs(Boolean.parseBoolean(String.valueOf(body.get("requiresJs"))));
            if (body.containsKey("categories") && body.get("categories") instanceof List<?> cats) {
                s.setCategories(cats.stream().map(String::valueOf).toList());
            }
            s.setUpdatedAt(LocalDateTime.now());
            shopRepository.save(s);
            return ResponseEntity.ok(s);
        } catch (DataAccessException e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /** Bulk-disable many shops at once. Body: {"slugs": [...], "status": "blocked"}. */
    @PostMapping("/shops/bulk-status")
    public ResponseEntity<?> bulkStatus(@RequestBody Map<String, Object> body) {
        Object rawSlugs = body.get("slugs");
        String status = (String) body.get("status");
        if (!(rawSlugs instanceof List<?> slugs) || slugs.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "slugs must be a non-empty array"));
        }
        if (status == null || !List.of("active", "blocked", "dormant", "draft").contains(status)) {
            return ResponseEntity.badRequest().body(Map.of("error", "status required"));
        }
        int updated = 0;
        for (Object o : slugs) {
            try {
                for (Shop s : shopRepository.findAllBySlug(String.valueOf(o))) {
                    s.setStatus(status);
                    s.setBlockedBy("active".equals(status) ? null : "operator");
                    if ("active".equals(status)) s.setConsecutiveFailures(0);
                    shopRepository.save(s);
                    updated++;
                }
            } catch (DataAccessException ignored) {}
        }
        if (updated > 0) CompletableFuture.runAsync(hotDrops::rebuild);
        return ResponseEntity.ok(Map.of("updated", updated, "status", status));
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

    // ─── Engagement Admin Endpoints ───
    
    @GetMapping("/feedback")
    public ResponseEntity<List<com.damKemon.dam.kemon.model.Feedback>> getAllFeedback() {
        List<com.damKemon.dam.kemon.model.Feedback> feedbacks = mongoTemplate.findAll(com.damKemon.dam.kemon.model.Feedback.class);
        feedbacks.sort((a, b) -> {
            if (a.getSubmittedAt() == null || b.getSubmittedAt() == null) return 0;
            return b.getSubmittedAt().compareTo(a.getSubmittedAt());
        });
        return ResponseEntity.ok(feedbacks);
    }

    private static String slugify(String name) {
        return name.toLowerCase().replaceAll("[^a-z0-9\\s-]", "")
                .replaceAll("\\s+", "-").replaceAll("-+", "-")
                .replaceAll("^-|-$", "");
    }
}
