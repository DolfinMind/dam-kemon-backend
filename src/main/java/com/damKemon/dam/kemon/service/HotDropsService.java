package com.damKemon.dam.kemon.service;

import com.damKemon.dam.kemon.config.AppRole;
import com.damKemon.dam.kemon.model.PriceHistory;
import com.damKemon.dam.kemon.model.Product;
import com.damKemon.dam.kemon.model.SitePrice;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.event.EventListener;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationOptions;
import org.springframework.data.mongodb.core.aggregation.DateOperators;
import org.springframework.data.mongodb.core.aggregation.Fields;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/**
 * Finds products whose current cheapest price is materially lower than its
 * recent typical market low — the "hot drops" rail on the homepage.
 *
 * <p>Rebuilds after the indexer + price-history snapshot finish. Only genuine
 * drops are published; ordinary catalog products never masquerade as drops.
 * The rolled-up result lives in the {@code hot-drops} cache and
 * is served straight to the public {@code /api/stats/hot-drops}.
 */
@Service
public class HotDropsService {

    private static final Logger log = LoggerFactory.getLogger(HotDropsService.class);
    private static final double MIN_DROP_RATIO = 1.03;   // a ≥3% drop qualifies (was 10%)
    private static final int HISTORY_DAYS = 7;
    private static final int RAIL_SIZE = 24;             // max rows the rail holds

    private final MongoTemplate mongo;
    private final AppRole appRole;
    private final CacheManager cacheManager;
    private final ShopVisibilityService shopVisibility;
    private final CategoryFocusService categoryFocus;
    private final AtomicBoolean rebuilding = new AtomicBoolean(false);

    /** A "drop" past this % of peak is a scraper mis-parse, not a deal ("৳55,500"→55
     *  reads as a 99.9% drop). Real BD tech discounts live well under this line. */
    @Value("${hot-drops.max-drop-pct:45}")
    private double maxDropPct;

    /** No computing/mobile product sells under this many taka — cheaper = junk parse. */
    @Value("${hot-drops.min-price:500}")
    private double minPlausiblePrice;

    @Value("${hot-drops.max-product-age-hours:72}")
    private long maxProductAgeHours;

    private volatile List<Map<String, Object>> latest = List.of();

    public HotDropsService(MongoTemplate mongo,
                           AppRole appRole,
                           CacheManager cacheManager,
                           ShopVisibilityService shopVisibility,
                           CategoryFocusService categoryFocus) {
        this.mongo = mongo;
        this.appRole = appRole;
        this.cacheManager = cacheManager;
        this.shopVisibility = shopVisibility;
        this.categoryFocus = categoryFocus;
    }

    /**
     * Rebuild once shortly after the web node boots, off the request thread, so a
     * fresh deploy shows drops without waiting for the next scheduled rebuild. Skipped on the
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
     * Rebuilt once daily after the price snapshot. The catalog crawl is nightly,
     * so more frequent runs only repeated the same result and wasted memory.
     */
    @Scheduled(cron = "${hot-drops.cron:0 30 4 * * *}")
    public void rebuild() {
        if (!appRole.isWeb()) return;
        if (!rebuilding.compareAndSet(false, true)) {
            log.info("HotDrops: rebuild already running — skipped overlapping trigger");
            return;
        }
        try {
            LocalDateTime cutoff = LocalDateTime.now().minusDays(HISTORY_DAYS);
            LocalDateTime freshnessCutoff = LocalDateTime.now().minusHours(maxProductAgeHours);

            // Stream the aggregation cursor instead of materialising every grouped
            // history row in AggregationResults. On a six-figure catalog this keeps
            // transient heap bounded while Mongo may spill the group stage to disk.
            Map<String, Double> referenceByOffer = new HashMap<>();
            try {
                Aggregation agg = Aggregation.newAggregation(
                        Aggregation.match(Criteria.where("recordedAt").gte(cutoff).and("price").gt(0)),
                        // First collapse every seller snapshot into that product's
                        // cheapest market price for the day. Comparing today's
                        // cheapest seller with history's most expensive seller was
                        // treating a normal shop spread as a permanent 70% "drop".
                        Aggregation.project("productId", "siteName", "price")
                                .and(DateOperators.DateToString.dateOf("recordedAt")
                                        .toString("%Y-%m-%d"))
                                .as("day"),
                        Aggregation.group(Fields.from(
                                        Fields.field("productId"),
                                        Fields.field("siteName"),
                                        Fields.field("day")))
                                .min("price").as("dailyLow"),
                        // A sustained seven-day reference beats a single historical
                        // maximum: one bad scrape can spike a day, but cannot dominate
                        // the average. Require at least three observed days.
                        Aggregation.group(Fields.from(
                                        Fields.field("productId", "_id.productId"),
                                        Fields.field("siteName", "_id.siteName")))
                                .avg("dailyLow").as("peak")
                                .count().as("days"),
                        Aggregation.match(Criteria.where("days").gte(3)))
                        .withOptions(AggregationOptions.builder()
                                .allowDiskUse(true)
                                .cursorBatchSize(1000)
                                .build());
                try (Stream<Document> stream =
                             mongo.aggregateStream(agg, PriceHistory.class, Document.class)) {
                    var cursor = stream.iterator();
                    while (cursor.hasNext()) {
                        Document d = cursor.next();
                        Document id = d.get("_id", Document.class);
                        Object peak = d.get("peak");
                        if (id != null && peak instanceof Number n) {
                            Object pid = id.get("productId");
                            Object site = id.get("siteName");
                            if (pid != null && site != null) {
                                referenceByOffer.put(offerKey(pid.toString(), site.toString()), n.doubleValue());
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("HotDrops: peak aggregation failed ({}) — keeping previous rail", e.getMessage());
                return;
            }

            // Stream a narrow Product projection one document at a time. The previous
            // Page<Product> loop retained 1,000 full catalog documents (including large
            // descriptions and metadata) per page and caused visible heap spikes.
            java.util.Set<String> hidden = shopVisibility.hiddenSlugs();
            List<Map<String, Object>> drops = new ArrayList<>();
            if (!referenceByOffer.isEmpty()) {
                Query query = new Query();
                query.fields()
                        .include("_id")
                        .include("slug")
                        .include("name")
                        .include("imageUrl")
                        .include("category")
                        .include("lowestPrice")
                        .include("lastScraped")
                        .include("updatedAt")
                        .include("prices");
                try (Stream<Product> stream = mongo.stream(query, Product.class)) {
                    var rows = stream.iterator();
                    while (rows.hasNext()) {
                        Product p = rows.next();
                        if (p.getId() == null) continue;
                        if (categoryFocus.isEnabled() && !categoryFocus.isAllowedLabel(p.getCategory())) continue;
                        LocalDateTime productFreshness = p.getLastScraped() != null ? p.getLastScraped() : p.getUpdatedAt();
                        if (productFreshness == null || productFreshness.isBefore(freshnessCutoff)) continue;
                        if (p.getPrices() == null || p.getPrices().isEmpty()) continue;

                        // Compare each current offer only with that same shop's own
                        // sustained history. Cross-shop price spreads are comparison
                        // value, not price drops, and must never enter this feed.
                        double bestPct = -1;
                        double bestCurrent = 0;
                        double bestReference = 0;
                        for (SitePrice sp : p.getPrices()) {
                            if (sp == null || sp.getPrice() == null || sp.getPrice() < minPlausiblePrice) continue;
                            String visibilitySlug = sp.getSiteSlug() != null ? sp.getSiteSlug() : sp.getSiteName();
                            if (visibilitySlug != null && hidden.contains(visibilitySlug.toLowerCase())) continue;
                            if (sp.getSiteName() == null) continue;
                            Double reference = referenceByOffer.get(offerKey(p.getId(), sp.getSiteName()));
                            if (reference == null) continue;
                            if (reference <= sp.getPrice() || reference < sp.getPrice() * MIN_DROP_RATIO) continue;
                            double pct = (reference - sp.getPrice()) / reference * 100.0;
                            if (pct >= maxDropPct || pct <= bestPct) continue;
                            bestPct = pct;
                            bestCurrent = sp.getPrice();
                            bestReference = reference;
                        }
                        if (bestPct > 0) {
                            drops.add(row(p, bestCurrent, bestReference,
                                    Math.round(bestPct * 10.0) / 10.0, sellers(p)));
                        }
                        drops = trimTop(drops, BY_DROP_PCT, RAIL_SIZE);
                    }
                } catch (Exception e) {
                    log.warn("HotDrops: product stream failed ({}) — keeping previous rail", e.getMessage());
                    return;
                }
                drops.sort(BY_DROP_PCT);
            }

            List<Map<String, Object>> out = drops.size() > RAIL_SIZE
                    ? List.copyOf(drops.subList(0, RAIL_SIZE))
                    : List.copyOf(drops);
            this.latest = out;
            evictCache();
            log.info("HotDrops: rebuilt — {} genuine drops published", out.size());
        } catch (Exception e) {
            log.warn("HotDrops: rebuild failed ({})", e.getMessage());
        } finally {
            rebuilding.set(false);
        }
    }

    private static final Comparator<Map<String, Object>> BY_DROP_PCT =
            Comparator.comparingDouble((Map<String, Object> m) -> (double) m.get("dropPct")).reversed();

    private static int sellers(Product p) {
        return p.getPrices() == null ? 0 : p.getPrices().size();
    }

    private static String offerKey(String productId, String siteName) {
        return productId + '\u001f' + siteName.trim().toLowerCase();
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
