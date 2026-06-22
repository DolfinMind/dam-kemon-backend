package com.damKemon.dam.kemon.service;

import com.damKemon.dam.kemon.config.AppRole;
import com.damKemon.dam.kemon.model.PriceHistory;
import com.damKemon.dam.kemon.model.Product;
import com.damKemon.dam.kemon.repository.ProductRepository;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Finds products whose current cheapest price is materially lower than its
 * recent peak — the "hot drops" rail on the homepage.
 *
 * <p>Rebuilds nightly after the indexer + price-history snapshot finish.
 * The rolled-up result lives in the {@code hot-drops} cache (60s TTL) and
 * is served straight to the public {@code /api/stats/hot-drops}.
 */
@Service
public class HotDropsService {

    private static final Logger log = LoggerFactory.getLogger(HotDropsService.class);
    private static final double MIN_DROP_RATIO = 1.03;   // a ≥3% drop qualifies (was 10%)
    private static final int HISTORY_DAYS = 7;
    private static final int RAIL_SIZE = 24;             // max rows the rail holds

    private final ProductRepository productRepository;
    private final MongoTemplate mongo;
    private final AppRole appRole;
    private final CacheManager cacheManager;

    private volatile List<Map<String, Object>> latest = List.of();

    public HotDropsService(ProductRepository productRepository,
                           MongoTemplate mongo,
                           AppRole appRole,
                           CacheManager cacheManager) {
        this.productRepository = productRepository;
        this.mongo = mongo;
        this.appRole = appRole;
        this.cacheManager = cacheManager;
    }

    /**
     * Rebuild once shortly after the web node boots, off the request thread, so a
     * fresh deploy shows drops without waiting for the 05:00 cron. Skipped on the
     * worker (no serving state there). This is what makes the rail INDEPENDENT of
     * the crawl: it recomputes from whatever price-history already exists, even if
     * the worker hasn't run — so "Hot drops" can never go stale because a crawl
     * was paused or crashed.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void rebuildOnStartup() {
        if (!appRole.isWeb()) return;
        CompletableFuture.runAsync(this::rebuild);
    }

    /**
     * Rebuilt every 4h so the rail tracks fresh crawls through the day instead of
     * going stale between nightly runs (00:00 still lands after the 03:00 indexer +
     * 04:00 snapshot of the previous cycle). Cheap enough to also run on demand.
     */
    @Scheduled(cron = "${hot-drops.cron:0 0 */4 * * *}")
    public void rebuild() {
        try {
            LocalDateTime cutoff = LocalDateTime.now().minusDays(HISTORY_DAYS);

            // ONE aggregation for the 7-day peak per product — replaces the old
            // per-product history query (an N+1 over the whole catalog that helped
            // wedge the JVM). Returns ~1 row per product, so it's heap-cheap.
            Map<String, Double> peakByProduct = new HashMap<>();
            try {
                Aggregation agg = Aggregation.newAggregation(
                        Aggregation.match(Criteria.where("recordedAt").gte(cutoff).and("price").gt(0)),
                        Aggregation.group("productId").max("price").as("peak"));
                for (Document d : mongo.aggregate(agg, PriceHistory.class, Document.class)) {
                    Object pid = d.get("_id");
                    Object peak = d.get("peak");
                    if (pid != null && peak instanceof Number n) peakByProduct.put(pid.toString(), n.doubleValue());
                }
            } catch (Exception e) {
                // No price history (e.g. fresh catalog) is fine — we still fill the rail
                // from current multi-seller products below, so it never freezes.
                log.warn("HotDrops: peak aggregation failed ({}) — building from current catalog only", e.getMessage());
            }

            // Page the catalog (heap-safe). Real drops go to `drops`; multi-seller
            // products with no measured drop become `fallback` filler so the rail is
            // never empty in a flat-price week (kept bounded as we go).
            List<Map<String, Object>> drops = new ArrayList<>();
            List<Map<String, Object>> fallback = new ArrayList<>();
            int page = 0;
            final int pageSize = 1000;
            while (true) {
                List<Product> rows;
                try {
                    rows = productRepository.findAll(PageRequest.of(page, pageSize)).getContent();
                } catch (DataAccessException e) {
                    log.warn("HotDrops: product scan failed ({}) — keeping previous rail", e.getMessage());
                    return;   // genuine read failure: keep the previous rail rather than blank it
                }
                if (rows.isEmpty()) break;
                for (Product p : rows) {
                    if (p.getId() == null || p.getLowestPrice() == null || p.getLowestPrice() <= 0) continue;
                    double current = p.getLowestPrice();
                    int sellers = p.getPrices() == null ? 0 : p.getPrices().size();
                    Double peak = peakByProduct.get(p.getId());
                    if (peak != null && peak > current && peak >= current * MIN_DROP_RATIO) {
                        double dropPct = (peak - current) / peak * 100.0;
                        drops.add(row(p, current, peak, Math.round(dropPct * 10.0) / 10.0, sellers));
                    } else if (sellers >= 2) {
                        fallback.add(row(p, current, current, 0.0, sellers));
                    }
                }
                fallback = trimTop(fallback, BY_SELLERS, 50);   // bound filler memory across pages
                page++;
                if (page > 1000) break;   // safety bound
            }
            drops.sort(BY_DROP_PCT);
            fallback.sort(BY_SELLERS);

            List<Map<String, Object>> out = new ArrayList<>(drops);
            for (Map<String, Object> f : fallback) {
                if (out.size() >= RAIL_SIZE) break;
                out.add(f);
            }
            if (out.size() > RAIL_SIZE) out = new ArrayList<>(out.subList(0, RAIL_SIZE));
            this.latest = out;
            evictCache();
            log.info("HotDrops: rebuilt — {} real drops, {} total shown", drops.size(), out.size());
        } catch (DataAccessException e) {
            log.warn("HotDrops: rebuild failed ({})", e.getMessage());
        }
    }

    private static final Comparator<Map<String, Object>> BY_DROP_PCT =
            Comparator.comparingDouble((Map<String, Object> m) -> (double) m.get("dropPct")).reversed();
    private static final Comparator<Map<String, Object>> BY_SELLERS =
            Comparator.comparingInt((Map<String, Object> m) -> (int) m.get("sellerCount")).reversed();

    private static Map<String, Object> row(Product p, double current, double peak, double dropPct, int sellers) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", p.getId());
        row.put("slug", p.getSlug());
        row.put("name", p.getName());
        row.put("imageUrl", p.getImageUrl());
        row.put("category", p.getCategory());
        row.put("currentPrice", current);
        row.put("peakPrice", peak);
        row.put("dropPct", dropPct);
        row.put("sellerCount", sellers);
        return row;
    }

    /** Keep the list bounded between pages: sort + cap once it grows past 4× the cap. */
    private static List<Map<String, Object>> trimTop(List<Map<String, Object>> list,
                                                     Comparator<Map<String, Object>> cmp, int cap) {
        if (list.size() <= cap * 4) return list;
        list.sort(cmp);
        return new ArrayList<>(list.subList(0, cap));
    }

    private void evictCache() {
        var c = cacheManager.getCache("hot-drops");
        if (c != null) c.clear();   // rebuild() updates `latest`; drop the cached pages so get() serves fresh
    }

    /** Number of products currently in the hot-drops set (for headline stats). */
    public int count() {
        return latest.size();
    }

    @Cacheable("hot-drops")
    public List<Map<String, Object>> get(int limit) {
        if (latest.size() <= limit) return latest;
        return new ArrayList<>(latest.subList(0, limit));
    }

    /** Snapshot of category counts in the current hot-drops set. */
    public Map<String, Integer> byCategory() {
        Map<String, Integer> agg = new HashMap<>();
        for (Map<String, Object> row : latest) {
            String c = (String) row.get("category");
            agg.merge(c == null ? "other" : c, 1, Integer::sum);
        }
        return agg;
    }
}
