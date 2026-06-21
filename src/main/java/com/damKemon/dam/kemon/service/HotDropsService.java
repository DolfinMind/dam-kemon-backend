package com.damKemon.dam.kemon.service;

import com.damKemon.dam.kemon.config.AppRole;
import com.damKemon.dam.kemon.model.PriceHistory;
import com.damKemon.dam.kemon.model.Product;
import com.damKemon.dam.kemon.repository.ProductRepository;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
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
    private static final double MIN_DROP_RATIO = 1.10;
    private static final int HISTORY_DAYS = 7;

    private final ProductRepository productRepository;
    private final MongoTemplate mongo;
    private final AppRole appRole;

    private volatile List<Map<String, Object>> latest = List.of();

    public HotDropsService(ProductRepository productRepository,
                           MongoTemplate mongo,
                           AppRole appRole) {
        this.productRepository = productRepository;
        this.mongo = mongo;
        this.appRole = appRole;
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
     * Run nightly at 05:00, after the indexer (03:00) + price snapshot (04:00).
     * Cheap enough to also run on demand from the admin endpoint.
     */
    @Scheduled(cron = "${hot-drops.cron:0 0 5 * * *}")
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
                log.warn("HotDrops: peak aggregation failed ({}) — keeping previous rail", e.getMessage());
                return;
            }
            if (peakByProduct.isEmpty()) {
                log.info("HotDrops: no price history in the last {}d — nothing to rebuild yet", HISTORY_DAYS);
                return;
            }

            // Page the catalog (heap-safe) and keep only the qualifying drops.
            List<Map<String, Object>> out = new ArrayList<>();
            int page = 0;
            final int pageSize = 1000;
            while (true) {
                List<Product> rows;
                try {
                    rows = productRepository.findAll(PageRequest.of(page, pageSize)).getContent();
                } catch (DataAccessException e) {
                    log.warn("HotDrops: product scan failed ({})", e.getMessage());
                    break;
                }
                if (rows.isEmpty()) break;
                for (Product p : rows) {
                    if (p.getId() == null || p.getLowestPrice() == null || p.getLowestPrice() <= 0) continue;
                    Double peak = peakByProduct.get(p.getId());
                    if (peak == null || peak <= 0) continue;
                    double current = p.getLowestPrice();
                    if (peak <= current || peak < current * MIN_DROP_RATIO) continue;

                    double dropPct = (peak - current) / peak * 100.0;
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", p.getId());
                    row.put("slug", p.getSlug());
                    row.put("name", p.getName());
                    row.put("imageUrl", p.getImageUrl());
                    row.put("category", p.getCategory());
                    row.put("currentPrice", current);
                    row.put("peakPrice", peak);
                    row.put("dropPct", Math.round(dropPct * 10.0) / 10.0);
                    row.put("sellerCount", p.getPrices() == null ? 0 : p.getPrices().size());
                    out.add(row);
                }
                page++;
                if (page > 1000) break;   // safety bound
            }
            out.sort(Comparator.comparingDouble((Map<String, Object> m) -> (double) m.get("dropPct")).reversed());
            if (out.size() > 24) out = new ArrayList<>(out.subList(0, 24));
            this.latest = out;
            log.info("HotDrops: rebuilt — {} qualifying products", out.size());
        } catch (DataAccessException e) {
            log.warn("HotDrops: rebuild failed ({})", e.getMessage());
        }
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
