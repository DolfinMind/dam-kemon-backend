package com.damKemon.dam.kemon.service;

import com.damKemon.dam.kemon.model.RequestLog;
import com.damKemon.dam.kemon.repository.RequestLogRepository;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.aggregation.ConditionalOperators;
import org.springframework.data.mongodb.core.aggregation.DateOperators;
import org.springframework.data.mongodb.core.aggregation.DateOperators.Timezone;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.data.mongodb.core.aggregation.Aggregation.count;
import static org.springframework.data.mongodb.core.aggregation.Aggregation.group;
import static org.springframework.data.mongodb.core.aggregation.Aggregation.limit;
import static org.springframework.data.mongodb.core.aggregation.Aggregation.match;
import static org.springframework.data.mongodb.core.aggregation.Aggregation.newAggregation;
import static org.springframework.data.mongodb.core.aggregation.Aggregation.project;
import static org.springframework.data.mongodb.core.aggregation.Aggregation.sort;
import static org.springframework.data.mongodb.core.query.Criteria.where;
import static org.springframework.data.mongodb.core.query.Query.query;

/**
 * The "powerful admin" analytics layer. Reads the {@code events} and
 * {@code request_log} collections via Mongo aggregation to answer the questions
 * the operator actually asks:
 *
 * <ul>
 *   <li>What's happening right now / today? — {@link #overview()}</li>
 *   <li>What are people searching most? — {@link #topSearches(int, int)}</li>
 *   <li>When is peak hour, and what do they search then? — {@link #hourly(int)}</li>
 *   <li>How many users per day, over time? — {@link #dailyUsers(int)}</li>
 *   <li>Who (which IPs) is hitting us most? — {@link #topIps(int, int)}</li>
 *   <li>Which endpoints get hit most? — {@link #topPaths(int, int)}</li>
 *   <li>Show me the raw request feed — {@link #recentRequests(int)}</li>
 * </ul>
 *
 * <p>Hour-of-day and per-day buckets are computed in the configured audience
 * timezone ({@code analytics.timezone}, default Asia/Dhaka), so "peak hour"
 * reflects the user's clock, not UTC. Every method degrades to empty/zero on a
 * transient Mongo error rather than throwing.
 */
@Service
public class AdminAnalyticsService {

    private static final String EVENTS = "events";
    private static final String REQUESTS = "request_log";
    private static final String CLICKS = "affiliate_clicks";
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE;

    private final MongoTemplate mongo;
    private final RequestLogRepository requestLog;
    private final ZoneId zone;
    private final Timezone tz;

    public AdminAnalyticsService(MongoTemplate mongo, RequestLogRepository requestLog,
                                 @Value("${analytics.timezone:Asia/Dhaka}") String timezone) {
        this.mongo = mongo;
        this.requestLog = requestLog;
        ZoneId z;
        try { z = ZoneId.of(timezone); } catch (Exception e) { z = ZoneId.of("Asia/Dhaka"); }
        this.zone = z;
        this.tz = Timezone.valueOf(z.getId());
    }

    // ───────────────────────── Overview (today + live) ─────────────────────────

    public Map<String, Object> overview() {
        Map<String, Object> out = new LinkedHashMap<>();
        Instant now = Instant.now();
        out.put("timezone", zone.getId());
        out.put("serverTime", now);
        try {
            Instant dayStart = todayStart();
            Instant hourAgo = now.minus(1, ChronoUnit.HOURS);
            Instant fiveMin = now.minus(5, ChronoUnit.MINUTES);

            out.put("requestsToday", mongo.count(query(where("ts").gte(dayStart)), REQUESTS));
            out.put("searchesToday", mongo.count(query(where("type").is("search").and("ts").gte(dayStart)), EVENTS));
            out.put("pageViewsToday", mongo.count(query(where("type").is("pageview").and("ts").gte(dayStart)), EVENTS));
            out.put("productViewsToday", mongo.count(query(where("type").is("view").and("ts").gte(dayStart)), EVENTS));
            out.put("clicksToday", mongo.count(query(where("type").is("click").and("ts").gte(dayStart)), EVENTS));
            out.put("visitorsToday", distinctVisitors(EVENTS, where("ts").gte(dayStart)));
            out.put("ipsToday", distinctField(REQUESTS, where("ts").gte(dayStart), "ip"));
            out.put("requestsLastHour", mongo.count(query(where("ts").gte(hourAgo)), REQUESTS));
            out.put("activeNow", distinctVisitors(REQUESTS, where("ts").gte(fiveMin)));
            out.put("totalRequestsRetained", mongo.count(query(new Criteria()), REQUESTS));
            
            // New Dashboard Metrics
            out.put("totalShops", mongo.count(query(new Criteria()), "shops"));
            out.put("failingShops", mongo.count(query(where("lastError").ne(null).not().regex("^\\s*$")), "shops"));
            out.put("totalCommunityReviews", mongo.count(query(where("source").is("community")), "reviews"));
            out.put("flaggedReviews", mongo.count(query(where("status").is("flagged")), "reviews"));
            long searchesToday = (long) out.getOrDefault("searchesToday", 0L);
            long clicksToday = (long) out.getOrDefault("clicksToday", 0L);
            out.put("searchConversionRate", searchesToday == 0 ? 0.0 : Math.round(((double) clicksToday / searchesToday) * 1000.0) / 10.0);
        } catch (Exception e) {
            out.putIfAbsent("requestsToday", 0L);
        }
        return out;
    }

    // ─────────────────────────── Most searched items ───────────────────────────

    public List<Map<String, Object>> topSearches(int days, int lim) {
        Instant cutoff = Instant.now().minus(days, ChronoUnit.DAYS);
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            Aggregation agg = newAggregation(
                    match(where("type").is("search").and("ts").gte(cutoff).and("query").ne(null)),
                    group("query")
                            .count().as("hits")
                            .sum(ConditionalOperators.when(where("resultCount").gt(0)).then(1).otherwise(0)).as("withResults")
                            .max("ts").as("lastSeen"),
                    sort(Sort.Direction.DESC, "hits"),
                    limit(lim));
            for (Document d : mongo.aggregate(agg, EVENTS, Document.class)) {
                long hits = num(d.get("hits"));
                long withResults = num(d.get("withResults"));
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("query", d.getString("_id"));
                row.put("hits", hits);
                row.put("withResults", withResults);
                row.put("zeroResults", hits - withResults);
                row.put("lastSeen", d.get("lastSeen"));
                out.add(row);
            }
        } catch (Exception ignored) { /* degrade to empty */ }
        return out;
    }

    // ──────────────────── Peak hour + what they search then ─────────────────────

    public Map<String, Object> hourly(int days) {
        Instant cutoff = Instant.now().minus(days, ChronoUnit.DAYS);
        long[] searches = hourBuckets(EVENTS, where("type").is("search").and("ts").gte(cutoff));
        long[] pageViews = hourBuckets(EVENTS, where("type").is("pageview").and("ts").gte(cutoff));
        long[] requests = hourBuckets(REQUESTS, where("ts").gte(cutoff));

        List<Map<String, Object>> buckets = new ArrayList<>(24);
        int peak = 0;
        long peakVal = -1;
        for (int h = 0; h < 24; h++) {
            long activity = searches[h] + pageViews[h];
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("hour", h);
            row.put("label", String.format("%02d:00", h));
            row.put("searches", searches[h]);
            row.put("pageViews", pageViews[h]);
            row.put("requests", requests[h]);
            row.put("activity", activity);
            buckets.add(row);
            if (activity > peakVal) { peakVal = activity; peak = h; }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("timezone", zone.getId());
        out.put("days", days);
        out.put("buckets", buckets);
        out.put("peakHour", peak);
        out.put("peakHourLabel", String.format("%02d:00–%02d:00", peak, (peak + 1) % 24));
        out.put("peakHourSearches", peakSearchTerms(cutoff, peak, 10));
        return out;
    }

    private long[] hourBuckets(String coll, Criteria c) {
        long[] arr = new long[24];
        try {
            Aggregation agg = newAggregation(
                    match(c),
                    project().and(DateOperators.dateOf("ts").withTimezone(tz).hour()).as("h"),
                    group("h").count().as("n"));
            for (Document d : mongo.aggregate(agg, coll, Document.class)) {
                Integer h = d.getInteger("_id");
                if (h != null && h >= 0 && h < 24) arr[h] = num(d.get("n"));
            }
        } catch (Exception ignored) { /* leave zeros */ }
        return arr;
    }

    /** Top search terms that landed inside the given local hour-of-day. */
    private List<Map<String, Object>> peakSearchTerms(Instant cutoff, int hour, int lim) {
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            Aggregation agg = newAggregation(
                    match(where("type").is("search").and("ts").gte(cutoff).and("query").ne(null)),
                    project("query").and(DateOperators.dateOf("ts").withTimezone(tz).hour()).as("h"),
                    match(where("h").is(hour)),
                    group("query").count().as("n"),
                    sort(Sort.Direction.DESC, "n"),
                    limit(lim));
            for (Document d : mongo.aggregate(agg, EVENTS, Document.class)) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("query", d.getString("_id"));
                row.put("hits", num(d.get("n")));
                out.add(row);
            }
        } catch (Exception ignored) { /* degrade to empty */ }
        return out;
    }

    // ──────────────────────────── Users per day ────────────────────────────────

    public List<Map<String, Object>> dailyUsers(int days) {
        LocalDate first = LocalDate.now(zone).minusDays(days - 1L);
        Instant cutoff = first.atStartOfDay(zone).toInstant();
        Map<String, Long> users = usersByDay(cutoff);
        Map<String, Long> searches = countByDay(EVENTS, where("type").is("search").and("ts").gte(cutoff));
        Map<String, Long> pageViews = countByDay(EVENTS, where("type").is("pageview").and("ts").gte(cutoff));
        Map<String, Long> requests = countByDay(REQUESTS, where("ts").gte(cutoff));

        List<Map<String, Object>> out = new ArrayList<>();
        LocalDate today = LocalDate.now(zone);
        for (LocalDate d = first; !d.isAfter(today); d = d.plusDays(1)) {
            String key = d.format(ISO);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", key);
            row.put("users", users.getOrDefault(key, 0L));
            row.put("searches", searches.getOrDefault(key, 0L));
            row.put("pageViews", pageViews.getOrDefault(key, 0L));
            row.put("requests", requests.getOrDefault(key, 0L));
            out.add(row);
        }
        return out;
    }

    /** Distinct anon ids per local day. */
    private Map<String, Long> usersByDay(Instant cutoff) {
        Map<String, Long> m = new HashMap<>();
        try {
            Aggregation agg = newAggregation(
                    match(where("ts").gte(cutoff).and("anonId").ne(null)),
                    project("anonId").and(DateOperators.dateOf("ts").withTimezone(tz).toString("%Y-%m-%d")).as("d"),
                    group("d", "anonId"),
                    group("_id.d").count().as("n"));
            for (Document d : mongo.aggregate(agg, EVENTS, Document.class)) {
                m.put(d.getString("_id"), num(d.get("n")));
            }
        } catch (Exception ignored) { /* degrade to empty */ }
        return m;
    }

    private Map<String, Long> countByDay(String coll, Criteria c) {
        Map<String, Long> m = new HashMap<>();
        try {
            Aggregation agg = newAggregation(
                    match(c),
                    project().and(DateOperators.dateOf("ts").withTimezone(tz).toString("%Y-%m-%d")).as("d"),
                    group("d").count().as("n"));
            for (Document d : mongo.aggregate(agg, coll, Document.class)) {
                m.put(d.getString("_id"), num(d.get("n")));
            }
        } catch (Exception ignored) { /* degrade to empty */ }
        return m;
    }

    // ─────────────────────── Who's hitting us (IPs) ─────────────────────────────

    public List<Map<String, Object>> topIps(int days, int lim) {
        Instant cutoff = Instant.now().minus(days, ChronoUnit.DAYS);
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            Aggregation agg = newAggregation(
                    match(where("ts").gte(cutoff)),
                    project("ip", "ipHash", "path", "userId", "anonId", "userAgent", "ts")
                            .and(ConditionalOperators.ifNull("ip")
                                    .thenValueOf(ConditionalOperators.ifNull("ipHash").then("unknown"))).as("ipKey"),
                    group("ipKey")
                            .count().as("requests")
                            .max("ts").as("lastSeen")
                            .last("ip").as("ip")
                            .last("ipHash").as("ipHash")
                            .last("userAgent").as("userAgent")
                            .addToSet("path").as("paths")
                            .addToSet("userId").as("userIds")
                            .addToSet("anonId").as("anonIds"),
                    sort(Sort.Direction.DESC, "requests"),
                    limit(lim));
            for (Document d : mongo.aggregate(agg, REQUESTS, Document.class)) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("ip", d.getString("ip"));
                row.put("ipHash", d.getString("ipHash"));
                row.put("requests", num(d.get("requests")));
                row.put("lastSeen", d.get("lastSeen"));
                row.put("userAgent", d.getString("userAgent"));
                row.put("distinctPaths", sizeOf(d.get("paths")));
                row.put("distinctVisitors", nonNullCount(d.get("anonIds")));
                row.put("userIds", nonNullStrings(d.get("userIds")));
                out.add(row);
            }
        } catch (Exception ignored) { /* degrade to empty */ }
        return out;
    }

    // ─────────────────────────── Busiest endpoints ─────────────────────────────

    public List<Map<String, Object>> topPaths(int days, int lim) {
        Instant cutoff = Instant.now().minus(days, ChronoUnit.DAYS);
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            Aggregation agg = newAggregation(
                    match(where("ts").gte(cutoff)),
                    group("path").count().as("hits").avg("latencyMs").as("avgLatency"),
                    sort(Sort.Direction.DESC, "hits"),
                    limit(lim));
            for (Document d : mongo.aggregate(agg, REQUESTS, Document.class)) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("path", d.getString("_id"));
                row.put("hits", num(d.get("hits")));
                Object avg = d.get("avgLatency");
                row.put("avgLatencyMs", avg instanceof Number ? Math.round(((Number) avg).doubleValue()) : null);
                out.add(row);
            }
        } catch (Exception ignored) { /* degrade to empty */ }
        return out;
    }

    // ─────────────────────────── Raw request feed ──────────────────────────────

    public List<RequestLog> recentRequests(int lim) {
        try {
            return requestLog.findAllByOrderByTsDesc(PageRequest.of(0, Math.max(1, Math.min(lim, 500))));
        } catch (Exception e) {
            return List.of();
        }
    }

    // ════════════════════ Outbound clicks (affiliate_clicks) ════════════════════
    // Deep marketplace signal: which shop wins which category, which products and
    // shops pull the most outbound traffic, and how search turns into clicks. All
    // read the denormalised category/productName written at click time.

    /**
     * Which shop is clicked most for which category. For each category, the shops
     * ranked by outbound clicks in the window; categories ordered by total clicks.
     * The aggregation sorts globally by clicks-desc, so as we bucket rows by
     * category each bucket already comes out highest-first.
     */
    public List<Map<String, Object>> shopClicksByCategory(int days, int maxCategories, int shopsPerCategory) {
        Instant cutoff = Instant.now().minus(days, ChronoUnit.DAYS);
        Map<String, List<Map<String, Object>>> byCat = new LinkedHashMap<>();
        Map<String, Long> catTotals = new LinkedHashMap<>();
        try {
            Aggregation agg = newAggregation(
                    match(where("ts").gte(cutoff)),
                    group("category", "siteSlug").count().as("clicks").max("ts").as("lastSeen"),
                    sort(Sort.Direction.DESC, "clicks"));
            for (Document d : mongo.aggregate(agg, CLICKS, Document.class)) {
                String category = "uncategorized", site = "unknown";
                if (d.get("_id") instanceof Document id) {
                    category = strOr(id.get("category"), "uncategorized");
                    site = strOr(id.get("siteSlug"), "unknown");
                }
                long clicks = num(d.get("clicks"));
                List<Map<String, Object>> shops = byCat.computeIfAbsent(category, k -> new ArrayList<>());
                if (shops.size() < shopsPerCategory) {
                    Map<String, Object> s = new LinkedHashMap<>();
                    s.put("siteSlug", site);
                    s.put("clicks", clicks);
                    s.put("lastSeen", d.get("lastSeen"));
                    shops.add(s);
                }
                catTotals.merge(category, clicks, Long::sum);
            }
        } catch (Exception ignored) { /* degrade to empty */ }

        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, List<Map<String, Object>>> e : byCat.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("category", e.getKey());
            row.put("totalClicks", catTotals.getOrDefault(e.getKey(), 0L));
            row.put("topShop", e.getValue().isEmpty() ? null : e.getValue().get(0).get("siteSlug"));
            row.put("shops", e.getValue());
            out.add(row);
        }
        out.sort((a, b) -> Long.compare((Long) b.get("totalClicks"), (Long) a.get("totalClicks")));
        return out.size() > maxCategories ? new ArrayList<>(out.subList(0, maxCategories)) : out;
    }

    /** Shops ranked by outbound clicks — who gets the most traffic we send out. */
    public List<Map<String, Object>> topShops(int days, int lim) {
        Instant cutoff = Instant.now().minus(days, ChronoUnit.DAYS);
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            Aggregation agg = newAggregation(
                    match(where("ts").gte(cutoff).and("siteSlug").ne(null)),
                    group("siteSlug")
                            .count().as("clicks")
                            .addToSet("productId").as("products")
                            .addToSet("anonId").as("visitors")
                            .addToSet("category").as("categories")
                            .max("ts").as("lastSeen"),
                    sort(Sort.Direction.DESC, "clicks"),
                    limit(lim));
            for (Document d : mongo.aggregate(agg, CLICKS, Document.class)) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("siteSlug", d.getString("_id"));
                row.put("clicks", num(d.get("clicks")));
                row.put("distinctProducts", sizeOf(d.get("products")));
                row.put("distinctVisitors", nonNullCount(d.get("visitors")));
                row.put("distinctCategories", nonNullCount(d.get("categories")));
                row.put("lastSeen", d.get("lastSeen"));
                out.add(row);
            }
        } catch (Exception ignored) { /* degrade to empty */ }
        return out;
    }

    /** Products ranked by outbound clicks — what shoppers actually click out to buy. */
    public List<Map<String, Object>> topClickedProducts(int days, int lim) {
        Instant cutoff = Instant.now().minus(days, ChronoUnit.DAYS);
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            Aggregation agg = newAggregation(
                    match(where("ts").gte(cutoff).and("productId").ne(null)),
                    group("productId")
                            .count().as("clicks")
                            .last("productName").as("name")
                            .last("category").as("category")
                            .addToSet("siteSlug").as("shops")
                            .max("ts").as("lastSeen"),
                    sort(Sort.Direction.DESC, "clicks"),
                    limit(lim));
            for (Document d : mongo.aggregate(agg, CLICKS, Document.class)) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("productId", d.getString("_id"));
                row.put("name", d.getString("name"));
                row.put("category", d.getString("category"));
                row.put("clicks", num(d.get("clicks")));
                row.put("distinctShops", sizeOf(d.get("shops")));
                row.put("lastSeen", d.get("lastSeen"));
                out.add(row);
            }
        } catch (Exception ignored) { /* degrade to empty */ }
        return out;
    }

    /** Search terms that drove the most outbound clicks (search → click attribution). */
    public List<Map<String, Object>> topConvertingSearches(int days, int lim) {
        Instant cutoff = Instant.now().minus(days, ChronoUnit.DAYS);
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            Aggregation agg = newAggregation(
                    match(where("ts").gte(cutoff).and("fromQuery").ne(null).and("fromQuery").ne("")),
                    group("fromQuery")
                            .count().as("clicks")
                            .addToSet("productId").as("products")
                            .addToSet("siteSlug").as("shops"),
                    sort(Sort.Direction.DESC, "clicks"),
                    limit(lim));
            for (Document d : mongo.aggregate(agg, CLICKS, Document.class)) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("query", d.getString("_id"));
                row.put("clicks", num(d.get("clicks")));
                row.put("distinctProducts", sizeOf(d.get("products")));
                row.put("distinctShops", sizeOf(d.get("shops")));
                out.add(row);
            }
        } catch (Exception ignored) { /* degrade to empty */ }
        return out;
    }

    /**
     * The marketplace funnel over the window: searches → product views → outbound
     * clicks, with stage-to-stage conversion rates. Searches/views come from
     * {@code events}; the authoritative outbound count from {@code affiliate_clicks}.
     */
    public Map<String, Object> clickFunnel(int days) {
        Instant cutoff = Instant.now().minus(days, ChronoUnit.DAYS);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("days", days);
        try {
            long searches = mongo.count(query(where("type").is("search").and("ts").gte(cutoff)), EVENTS);
            long views = mongo.count(query(where("type").is("view").and("ts").gte(cutoff)), EVENTS);
            long clicks = mongo.count(query(where("ts").gte(cutoff)), CLICKS);
            out.put("searches", searches);
            out.put("productViews", views);
            out.put("outboundClicks", clicks);
            out.put("searchToView", pct(views, searches));
            out.put("viewToClick", pct(clicks, views));
            out.put("searchToClick", pct(clicks, searches));
        } catch (Exception e) {
            out.put("searches", 0L);
            out.put("productViews", 0L);
            out.put("outboundClicks", 0L);
        }
        return out;
    }

    // ──────────────────────────────── helpers ──────────────────────────────────

    private Instant todayStart() {
        return LocalDate.now(zone).atStartOfDay(zone).toInstant();
    }

    /** Distinct "visitor" = anonId, falling back to ipHash, then "unknown". */
    private long distinctVisitors(String coll, Criteria c) {
        try {
            Aggregation agg = newAggregation(
                    match(c),
                    project().and(ConditionalOperators.ifNull("anonId")
                            .thenValueOf(ConditionalOperators.ifNull("ipHash").then("unknown"))).as("v"),
                    group("v"),
                    count().as("n"));
            AggregationResults<Document> r = mongo.aggregate(agg, coll, Document.class);
            Document d = r.getUniqueMappedResult();
            return d == null ? 0 : num(d.get("n"));
        } catch (Exception e) {
            return 0;
        }
    }

    private long distinctField(String coll, Criteria base, String field) {
        try {
            return mongo.findDistinct(query(base.and(field).ne(null)), field, coll, String.class).size();
        } catch (Exception e) {
            return 0;
        }
    }

    private static long num(Object o) {
        return o instanceof Number ? ((Number) o).longValue() : 0L;
    }

    /** First non-blank string form of {@code o}, else {@code dflt}. */
    private static String strOr(Object o, String dflt) {
        if (o == null) return dflt;
        String s = String.valueOf(o);
        return s.isBlank() ? dflt : s;
    }

    /** {@code a} as a percentage of {@code b} (0–100, one decimal); null when b≤0. */
    private static Double pct(long a, long b) {
        if (b <= 0) return null;
        return Math.round((a * 1000.0) / b) / 10.0;
    }

    private static int sizeOf(Object o) {
        return o instanceof Collection ? ((Collection<?>) o).size() : 0;
    }

    private static int nonNullCount(Object o) {
        if (!(o instanceof Collection<?> c)) return 0;
        int n = 0;
        for (Object x : c) if (x != null) n++;
        return n;
    }

    private static List<String> nonNullStrings(Object o) {
        List<String> out = new ArrayList<>();
        if (o instanceof Collection<?> c) {
            for (Object x : c) if (x != null) out.add(String.valueOf(x));
        }
        return out;
    }
}
