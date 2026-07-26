package com.damKemon.dam.kemon.service;

import com.damKemon.dam.kemon.model.RequestLog;
import com.damKemon.dam.kemon.repository.RequestLogRepository;
import com.damKemon.dam.kemon.util.TrafficClassifier;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.aggregation.ArrayOperators;
import org.springframework.data.mongodb.core.aggregation.ConditionalOperators;
import org.springframework.data.mongodb.core.aggregation.DateOperators;
import org.springframework.data.mongodb.core.aggregation.DateOperators.Timezone;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
import static org.springframework.data.mongodb.core.aggregation.Aggregation.unwind;
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
    private final boolean cleanTrafficEnabled;

    public AdminAnalyticsService(MongoTemplate mongo, RequestLogRepository requestLog,
                                 @Value("${analytics.timezone:Asia/Dhaka}") String timezone,
                                 @Value("${analytics.clean-traffic.enabled:true}") boolean cleanTrafficEnabled) {
        this.mongo = mongo;
        this.requestLog = requestLog;
        this.cleanTrafficEnabled = cleanTrafficEnabled;
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
            Instant fiveMin = now.minus(5, ChronoUnit.MINUTES);

            out.put("searchesToday", mongo.count(query(human(where("type").is("search").and("ts").gte(dayStart))), EVENTS));
            out.put("uniqueSearchersToday", distinctVisitors(EVENTS,
                    human(where("type").is("search").and("ts").gte(dayStart))));
            Criteria humanPageViewsToday = human(publicPageView(where("ts").gte(dayStart)));
            out.put("pageViewsToday", mongo.count(query(humanPageViewsToday), EVENTS));
            out.put("productViewsToday", mongo.count(query(human(where("type").is("view").and("ts").gte(dayStart))), EVENTS));
            out.put("clicksToday", mongo.count(query(human(where("ts").gte(dayStart))), CLICKS));
            long likelyHumanVisitors = distinctVisitors(EVENTS, human(publicPageView(where("ts").gte(dayStart))));
            out.put("likelyHumanVisitorsToday", likelyHumanVisitors);
            out.put("visitorsToday", likelyHumanVisitors);
            out.put("knownBotVisitorsToday", distinctVisitors(EVENTS,
                    publicPageView(where("ts").gte(dayStart).and("trafficClass").is(TrafficClassifier.KNOWN_BOT))));
            out.put("suspectedBotVisitorsToday", distinctVisitors(EVENTS,
                    publicPageView(where("ts").gte(dayStart).and("trafficClass").is(TrafficClassifier.SUSPECTED_BOT))));
            out.put("unclassifiedVisitorsToday", distinctVisitors(EVENTS,
                    unclassified(publicPageView(where("ts").gte(dayStart)))));
            out.put("activeNow", distinctVisitors(EVENTS, human(publicPageView(where("ts").gte(fiveMin)))));
            long searchesToday = (long) out.getOrDefault("searchesToday", 0L);
            long clicksToday = (long) out.getOrDefault("clicksToday", 0L);
            double clicksPer100Searches = searchesToday == 0 ? 0.0
                    : Math.round(((double) clicksToday / searchesToday) * 1000.0) / 10.0;
            out.put("clicksPer100Searches", clicksPer100Searches);
            out.put("searchConversionRate", clicksPer100Searches);
            out.put("cleanTrafficEnabled", cleanTrafficEnabled);
        } catch (Exception e) {
            out.putIfAbsent("searchesToday", 0L);
        }
        return out;
    }

    // ─────────────────────────── Catalog growth ───────────────────────────────

    /** Product additions over time plus the current seller/shop footprint. */
    public Map<String, Object> catalogGrowth(int days) {
        LocalDate first = LocalDate.now(zone).minusDays(days - 1L);
        LocalDateTime cutoff = first.atStartOfDay();
        Map<String, Long> created = new HashMap<>();
        try {
            Aggregation agg = newAggregation(
                    match(where("createdAt").gte(cutoff)),
                    project().and(DateOperators.dateOf("createdAt").withTimezone(tz)
                            .toString("%Y-%m-%d")).as("d"),
                    group("d").count().as("n"));
            for (Document d : mongo.aggregate(agg, "products", Document.class)) {
                created.put(d.getString("_id"), num(d.get("n")));
            }
        } catch (Exception ignored) { /* degrade to zeros */ }

        List<Map<String, Object>> daily = new ArrayList<>();
        long newProducts = 0;
        LocalDate today = LocalDate.now(zone);
        for (LocalDate d = first; !d.isAfter(today); d = d.plusDays(1)) {
            String key = d.format(ISO);
            long n = created.getOrDefault(key, 0L);
            newProducts += n;
            daily.add(Map.of("date", key, "products", n));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("days", days);
        out.put("totalProducts", safeCount("products", new Criteria()));
        out.put("totalSellers", safeCount("sellers", new Criteria()));
        out.put("marketplaceSellers", safeCount("marketplace_sellers", new Criteria()));
        out.put("activeShops", safeCount("shops", where("status").is("active")));
        out.put("recoverableShops", safeCount("shops",
                where("status").is("blocked").and("blockedBy").ne("operator")));
        out.put("newProducts", newProducts);
        out.put("daily", daily);
        return out;
    }

    // ─────────────────────────── Most searched items ───────────────────────────

    public List<Map<String, Object>> topSearches(int days, int lim) {
        Instant cutoff = Instant.now().minus(days, ChronoUnit.DAYS);
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            Aggregation agg = newAggregation(
                    match(human(where("type").is("search").and("ts").gte(cutoff).and("query").ne(null))),
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
        long[] searches = hourBuckets(EVENTS, human(where("type").is("search").and("ts").gte(cutoff)));
        long[] pageViews = hourBuckets(EVENTS, human(publicPageView(where("ts").gte(cutoff))));

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
                    match(human(where("type").is("search").and("ts").gte(cutoff).and("query").ne(null))),
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
        Map<String, Long> searches = countByDay(EVENTS, human(where("type").is("search").and("ts").gte(cutoff)));
        Map<String, Long> pageViews = countByDay(EVENTS, human(publicPageView(where("ts").gte(cutoff))));

        List<Map<String, Object>> out = new ArrayList<>();
        LocalDate today = LocalDate.now(zone);
        for (LocalDate d = first; !d.isAfter(today); d = d.plusDays(1)) {
            String key = d.format(ISO);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", key);
            row.put("users", users.getOrDefault(key, 0L));
            row.put("searches", searches.getOrDefault(key, 0L));
            row.put("pageViews", pageViews.getOrDefault(key, 0L));
            out.add(row);
        }
        return out;
    }

    /** Distinct likely-human public page-view anon ids per local day. */
    private Map<String, Long> usersByDay(Instant cutoff) {
        Map<String, Long> m = new HashMap<>();
        try {
            Aggregation agg = newAggregation(
                    match(human(publicPageView(where("ts").gte(cutoff).and("anonId").ne(null)))),
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

    // ─────────────────────── Devices & referrers (pageviews) ───────────────────

    /** Public page-view split: likely-human devices plus bot and unclassified traffic. */
    public Map<String, Object> deviceBreakdown(int days) {
        Instant cutoff = Instant.now().minus(days, ChronoUnit.DAYS);
        Map<String, long[]> buckets = new LinkedHashMap<>();   // bucket -> [views, uniques]
        for (String b : List.of("mobile", "tablet", "desktop", "known_bot",
                "suspected_bot", "unclassified"))
            buckets.put(b, new long[2]);
        try {
            Aggregation agg = newAggregation(
                    match(publicPageView(where("ts").gte(cutoff))),
                    group("trafficClass", "userAgent").count().as("views").addToSet("anonId").as("anonIds"));
            for (Document d : mongo.aggregate(agg, EVENTS, Document.class)) {
                Document id = d.get("_id") instanceof Document value ? value : new Document();
                String trafficClass = id.getString("trafficClass");
                String bucket = switch (trafficClass == null ? TrafficClassifier.UNCLASSIFIED : trafficClass) {
                    case TrafficClassifier.LIKELY_HUMAN -> classifyUa(id.getString("userAgent"));
                    case TrafficClassifier.KNOWN_BOT -> "known_bot";
                    case TrafficClassifier.SUSPECTED_BOT -> "suspected_bot";
                    default -> "unclassified";
                };
                if (!buckets.containsKey(bucket)) bucket = "unclassified";
                long[] acc = buckets.get(bucket);
                acc[0] += num(d.get("views"));
                acc[1] += nonNullCount(d.get("anonIds"));
            }
        } catch (Exception ignored) { /* degrade to zeros */ }
        List<Map<String, Object>> rows = new ArrayList<>();
        long total = 0;
        for (long[] v : buckets.values()) total += v[0];
        for (Map.Entry<String, long[]> e : buckets.entrySet()) {
            if (e.getValue()[0] == 0) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("device", e.getKey().replace('_', ' '));
            row.put("views", e.getValue()[0]);
            row.put("visitors", e.getValue()[1]);
            row.put("pct", total == 0 ? 0.0 : Math.round(e.getValue()[0] * 1000.0 / total) / 10.0);
            rows.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("totalViews", total);
        out.put("devices", rows);
        return out;
    }

    /** Where visitors come from: pageview referrers bucketed by host, direct = no referrer. */
    public List<Map<String, Object>> topReferrers(int days, int lim) {
        Instant cutoff = Instant.now().minus(days, ChronoUnit.DAYS);
        Map<String, long[]> hosts = new HashMap<>();           // host -> [views, uniques]
        try {
            Aggregation agg = newAggregation(
                    match(human(publicPageView(where("ts").gte(cutoff)))),
                    group("referer").count().as("views").addToSet("anonId").as("anonIds"));
            for (Document d : mongo.aggregate(agg, EVENTS, Document.class)) {
                String host = refererHost(d.getString("_id"));
                long[] acc = hosts.computeIfAbsent(host, k -> new long[2]);
                acc[0] += num(d.get("views"));
                acc[1] += nonNullCount(d.get("anonIds"));
            }
        } catch (Exception ignored) { /* degrade to empty */ }
        return hosts.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]))
                .limit(lim)
                .map(e -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("referrer", e.getKey());
                    row.put("views", e.getValue()[0]);
                    row.put("visitors", e.getValue()[1]);
                    return row;
                })
                .collect(java.util.stream.Collectors.toList());
    }

    private static String classifyUa(String ua) {
        if (ua == null || ua.isBlank()) return "unknown";
        if (TrafficClassifier.isKnownBotUa(ua)) return "bot";
        String s = ua.toLowerCase();
        if (s.contains("ipad") || (s.contains("tablet") && !s.contains("mobile"))) return "tablet";
        if (s.contains("mobi") || s.contains("android") || s.contains("iphone")) return "mobile";
        return "desktop";
    }

    private static String refererHost(String referer) {
        if (referer == null || referer.isBlank()) return "(direct)";
        try {
            String host = java.net.URI.create(referer.trim()).getHost();
            if (host == null) return "(other)";
            return host.startsWith("www.") ? host.substring(4) : host;
        } catch (Exception e) {
            return "(other)";
        }
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
                    match(human(where("ts").gte(cutoff))),
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
                    match(human(where("ts").gte(cutoff).and("siteSlug").ne(null))),
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
                    match(human(where("ts").gte(cutoff).and("productId").ne(null))),
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
                    match(human(where("ts").gte(cutoff).and("fromQuery").ne(null).and("fromQuery").ne(""))),
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
            long searches = mongo.count(query(human(where("type").is("search").and("ts").gte(cutoff))), EVENTS);
            long views = mongo.count(query(human(where("type").is("view").and("ts").gte(cutoff))), EVENTS);
            long clicks = mongo.count(query(human(where("ts").gte(cutoff))), CLICKS);
            out.put("searches", searches);
            out.put("productViews", views);
            out.put("outboundClicks", clicks);
            out.put("searchToView", pct(views, searches));
            out.put("viewToClick", pct(clicks, views));
            out.put("searchToClick", pct(clicks, searches));
            out.put("clicksPer100Views", pct(clicks, views));
            out.put("clicksPer100Searches", pct(clicks, searches));
        } catch (Exception e) {
            out.put("searches", 0L);
            out.put("productViews", 0L);
            out.put("outboundClicks", 0L);
        }
        return out;
    }

    // ════════════════════ Search result ranking & gaps ══════════════════════════

    /**
     * Which shops appear first in search results. For each search we logged the
     * cheapest-offer shop of every result product in ranked order; this counts how
     * often each shop took the #1 slot (what the shopper sees first) and how often
     * it appeared anywhere in the top results.
     */
    public List<Map<String, Object>> topResultShops(int days, int lim) {
        Instant cutoff = Instant.now().minus(days, ChronoUnit.DAYS);
        Map<String, long[]> byShop = new LinkedHashMap<>();   // slug -> [shownFirst, appears]
        long totalFirst = 0;
        try {
            Aggregation first = newAggregation(
                    match(human(where("type").is("search").and("ts").gte(cutoff).and("resultShops").ne(null))),
                    project().and(ArrayOperators.ArrayElemAt.arrayOf("resultShops").elementAt(0)).as("shop"),
                    group("shop").count().as("n"),
                    sort(Sort.Direction.DESC, "n"));
            for (Document d : mongo.aggregate(first, EVENTS, Document.class)) {
                String slug = strOr(d.get("_id"), "unknown");
                long n = num(d.get("n"));
                byShop.computeIfAbsent(slug, k -> new long[2])[0] = n;
                totalFirst += n;
            }
            Aggregation appears = newAggregation(
                    match(human(where("type").is("search").and("ts").gte(cutoff).and("resultShops").ne(null))),
                    unwind("resultShops"),
                    group("resultShops").count().as("n"));
            for (Document d : mongo.aggregate(appears, EVENTS, Document.class)) {
                byShop.computeIfAbsent(strOr(d.get("_id"), "unknown"), k -> new long[2])[1] = num(d.get("n"));
            }
        } catch (Exception ignored) { /* degrade to empty */ }

        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, long[]> e : byShop.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("siteSlug", e.getKey());
            row.put("shownFirst", e.getValue()[0]);
            row.put("appears", e.getValue()[1]);
            row.put("shownFirstPct", pct(e.getValue()[0], totalFirst));
            out.add(row);
        }
        out.sort((a, b) -> Long.compare((Long) b.get("shownFirst"), (Long) a.get("shownFirst")));
        return out.size() > lim ? new ArrayList<>(out.subList(0, lim)) : out;
    }

    /** Searches that returned nothing — unmet demand / catalog gaps to fill. */
    public List<Map<String, Object>> zeroResultSearches(int days, int lim) {
        Instant cutoff = Instant.now().minus(days, ChronoUnit.DAYS);
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            Aggregation agg = newAggregation(
                    match(human(where("type").is("search").and("ts").gte(cutoff)
                            .and("query").ne(null).and("resultCount").lte(0))),
                    group("query").count().as("hits").max("ts").as("lastSeen"),
                    sort(Sort.Direction.DESC, "hits"),
                    limit(lim));
            for (Document d : mongo.aggregate(agg, EVENTS, Document.class)) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("query", d.getString("_id"));
                row.put("hits", num(d.get("hits")));
                row.put("lastSeen", d.get("lastSeen"));
                out.add(row);
            }
        } catch (Exception ignored) { /* degrade to empty */ }
        return out;
    }

    /**
     * How often each shop holds the cheapest offer when it appears in a product's
     * comparison — the shop "price-win rate". Computed live over the catalog:
     * appearances = offers per shop; wins = times that shop is the cheapest on a
     * product. ponytail: the wins pass sorts all unwound offers; fine for an
     * on-demand admin call — add allowDiskUse if the catalog grows 10×.
     */
    public List<Map<String, Object>> shopPriceWins(int lim) {
        Map<String, long[]> byShop = new LinkedHashMap<>();   // slug -> [wins, appearances]
        try {
            Aggregation appears = newAggregation(
                    unwind("prices"),
                    match(where("prices.price").gt(0).and("prices.siteSlug").ne(null)),
                    group("prices.siteSlug").count().as("n"));
            for (Document d : mongo.aggregate(appears, "products", Document.class)) {
                byShop.computeIfAbsent(strOr(d.get("_id"), "unknown"), k -> new long[2])[1] = num(d.get("n"));
            }
            Aggregation wins = newAggregation(
                    unwind("prices"),
                    match(where("prices.price").gt(0).and("prices.siteSlug").ne(null)),
                    sort(Sort.Direction.ASC, "prices.price"),
                    group("_id").first("prices.siteSlug").as("winner"),
                    group("winner").count().as("n"));
            for (Document d : mongo.aggregate(wins, "products", Document.class)) {
                byShop.computeIfAbsent(strOr(d.get("_id"), "unknown"), k -> new long[2])[0] = num(d.get("n"));
            }
        } catch (Exception ignored) { /* degrade to empty */ }

        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, long[]> e : byShop.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("siteSlug", e.getKey());
            row.put("wins", e.getValue()[0]);
            row.put("appearances", e.getValue()[1]);
            row.put("winRate", pct(e.getValue()[0], e.getValue()[1]));
            out.add(row);
        }
        out.sort((a, b) -> Long.compare((Long) b.get("appearances"), (Long) a.get("appearances")));
        return out.size() > lim ? new ArrayList<>(out.subList(0, lim)) : out;
    }

    // ──────────────────────────────── helpers ──────────────────────────────────

    private Instant todayStart() {
        return LocalDate.now(zone).atStartOfDay(zone).toInstant();
    }

    /** New rows use the persisted class; the flag gives operations a query-only rollback. */
    private Criteria human(Criteria c) {
        if (cleanTrafficEnabled) return c.and("trafficClass").is(TrafficClassifier.LIKELY_HUMAN);
        return c.and("userAgent").not().regex(TrafficClassifier.knownBotUserAgentPattern())
                .and("ip").not().regex(TrafficClassifier.suspectedRendererIpPattern());
    }

    private static Criteria publicPageView(Criteria c) {
        return c.and("type").is("pageview").and("path").not().regex("^/admin(?:/|$)");
    }

    private static Criteria unclassified(Criteria c) {
        return new Criteria().andOperator(c, new Criteria().orOperator(
                where("trafficClass").is(null),
                where("trafficClass").is(TrafficClassifier.UNCLASSIFIED)));
    }

    /** Distinct visitor = anonId, falling back to ipHash; rows with neither are ignored. */
    private long distinctVisitors(String coll, Criteria c) {
        try {
            Aggregation agg = newAggregation(
                    match(c),
                    match(new Criteria().orOperator(
                            where("anonId").exists(true).ne(null),
                            where("ipHash").exists(true).ne(null))),
                    project().and(ConditionalOperators.ifNull("anonId")
                            .thenValueOf("ipHash")).as("v"),
                    group("v"),
                    count().as("n"));
            AggregationResults<Document> r = mongo.aggregate(agg, coll, Document.class);
            Document d = r.getUniqueMappedResult();
            return d == null ? 0 : num(d.get("n"));
        } catch (Exception e) {
            return 0;
        }
    }

    private long safeCount(String coll, Criteria criteria) {
        try {
            return mongo.count(query(criteria), coll);
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
