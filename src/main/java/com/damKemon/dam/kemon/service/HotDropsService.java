package com.damKemon.dam.kemon.service;

import com.damKemon.dam.kemon.config.AppRole;
import com.damKemon.dam.kemon.model.PriceHistory;
import com.damKemon.dam.kemon.model.Product;
import com.damKemon.dam.kemon.model.SitePrice;
import com.damKemon.dam.kemon.repository.ProductRepository;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
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
    private final ShopVisibilityService shopVisibility;

    /** A "drop" past this % of peak is a scraper mis-parse, not a deal ("৳55,500"→55
     *  reads as a 99.9% drop). Real BD tech discounts live well under this line. */
    @Value("${hot-drops.max-drop-pct:70}")
    private double maxDropPct;

    /** No computing/mobile product sells under this many taka — cheaper = junk parse. */
    @Value("${hot-drops.min-price:200}")
    private double minPlausiblePrice;

    private volatile List<Map<String, Object>> latest = List.of();

    public HotDropsService(ProductRepository productRepository,
                           MongoTemplate mongo,
                           AppRole appRole,
                           CacheManager cacheManager,
                           ShopVisibilityService shopVisibility) {
        this.productRepository = productRepository;
        this.mongo = mongo;
        this.appRole = appRole;
        this.cacheManager = cacheManager;
        this.shopVisibility = shopVisibility;
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

            // Real drops first — only worth scanning the whole catalog if we actually
            // have price history to compare against (skip the scan entirely when not).
            java.util.Set<String> hidden = shopVisibility.hiddenSlugs();
            List<Map<String, Object>> drops = new ArrayList<>();
            if (!peakByProduct.isEmpty()) {
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
                        if (p.getId() == null) continue;
                        Double peak = peakByProduct.get(p.getId());
                        if (peak == null) continue;
                        // Cheapest offer from a VISIBLE shop only — a hidden shop's
                        // (often junk) price must never headline the homepage rail.
                        Double cur = visibleLowest(p, hidden);
                        if (cur == null || cur < minPlausiblePrice) continue;
                        double current = cur;
                        if (peak > current && peak >= current * MIN_DROP_RATIO) {
                            double dropPct = (peak - current) / peak * 100.0;
                            if (dropPct > maxDropPct) continue;   // impossible drop = mis-parsed price
                            drops.add(row(p, current, peak, Math.round(dropPct * 10.0) / 10.0, sellers(p)));
                        }
                    }
                    drops = trimTop(drops, BY_DROP_PCT, RAIL_SIZE);
                    page++;
                    if (page > 1000) break;   // safety bound
                }
                drops.sort(BY_DROP_PCT);
            }

            List<Map<String, Object>> out =
                    new ArrayList<>(drops.size() > RAIL_SIZE ? drops.subList(0, RAIL_SIZE) : drops);

            // Fill the remainder with the newest priced products so the rail is NEVER
            // empty (this catalog averages ~1 seller/product, so a "2+ sellers" filler
            // gate left it blank) and refreshes as the catalog grows.
            // ponytail: _id desc ≈ newest-added, uses the default index; switch to
            // updatedAt if "recently re-priced" beats "recently added".
            if (out.size() < RAIL_SIZE) {
                java.util.Set<String> have = new java.util.HashSet<>();
                for (Map<String, Object> m : out) have.add((String) m.get("id"));
                try {
                    List<Product> fresh = productRepository.findAll(
                            PageRequest.of(0, RAIL_SIZE * 4, Sort.by(Sort.Direction.DESC, "_id"))).getContent();
                    for (Product p : fresh) {
                        if (out.size() >= RAIL_SIZE) break;
                        if (p.getId() == null || have.contains(p.getId())) continue;
                        Double cur = visibleLowest(p, hidden);
                        if (cur == null || cur < minPlausiblePrice) continue;
                        out.add(row(p, cur, cur, 0.0, sellers(p)));
                    }
                } catch (DataAccessException e) {
                    log.warn("HotDrops: fallback fill failed ({})", e.getMessage());
                }
            }

            this.latest = out;
            evictCache();
            log.info("HotDrops: rebuilt — {} real drops, {} total shown", drops.size(), out.size());
        } catch (DataAccessException e) {
            log.warn("HotDrops: rebuild failed ({})", e.getMessage());
        }
    }

    private static final Comparator<Map<String, Object>> BY_DROP_PCT =
            Comparator.comparingDouble((Map<String, Object> m) -> (double) m.get("dropPct")).reversed();

    private static int sellers(Product p) {
        return p.getPrices() == null ? 0 : p.getPrices().size();
    }

    /** Cheapest positive price among offers NOT from a hidden shop. Falls back to
     *  the stored aggregate when the doc carries no offer rows. Null = nothing
     *  visible to price this product with — the product sits the rail out. */
    static Double visibleLowest(Product p, java.util.Set<String> hidden) {
        if (p.getPrices() == null || p.getPrices().isEmpty()) {
            return (p.getLowestPrice() != null && p.getLowestPrice() > 0) ? p.getLowestPrice() : null;
        }
        Double best = null;
        for (SitePrice sp : p.getPrices()) {
            if (sp == null || sp.getPrice() == null || sp.getPrice() <= 0) continue;
            String slug = sp.getSiteSlug() != null ? sp.getSiteSlug() : sp.getSiteName();
            if (slug != null && hidden.contains(slug.toLowerCase())) continue;
            if (best == null || sp.getPrice() < best) best = sp.getPrice();
        }
        return best;
    }

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
