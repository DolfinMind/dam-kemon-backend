package com.damKemon.dam.kemon.service;

import com.damKemon.dam.kemon.model.AnalyticsEvent;
import com.damKemon.dam.kemon.model.RequestLog;
import com.damKemon.dam.kemon.model.User;
import com.damKemon.dam.kemon.repository.UserRepository;
import com.mongodb.client.MongoCollection;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class AdminUserService {

    // Lists, not Sets: the driver's $in encodes a List unambiguously across codec registries.
    private static final List<String> ACTIVE_EVENTS = List.of("search", "view", "click", "suggest_click");
    private static final List<String> ACTIVATION_EVENTS = List.of(
            "member_action_completed_save", "member_action_completed_track", "saved_search_created");

    private final MongoTemplate mongo;
    private final UserRepository users;

    public AdminUserService(MongoTemplate mongo, UserRepository users) {
        this.mongo = mongo;
        this.users = users;
    }

    public Map<String, Object> list(int page, int size, String search, int days) {
        Query query = new Query();
        if (search != null && !search.isBlank()) {
            Pattern term = Pattern.compile(Pattern.quote(search.trim()), Pattern.CASE_INSENSITIVE);
            query.addCriteria(new Criteria().orOperator(
                    Criteria.where("displayName").regex(term),
                    Criteria.where("email").regex(term),
                    Criteria.where("username").regex(term)));
        }

        long total = mongo.count(query, User.class);
        query.with(Sort.by(Sort.Direction.DESC, "createdAt"))
                .skip((long) page * size)
                .limit(size);
        List<User> pageUsers = mongo.find(query, User.class);
        List<String> ids = pageUsers.stream().map(User::getId).toList();
        Map<String, Document> activity = activitySummary(ids, days);

        List<Map<String, Object>> items = pageUsers.stream().map(user -> {
            Map<String, Object> row = safeUser(user);
            Document stats = activity.getOrDefault(user.getId(), new Document());
            row.put("activityCount", number(stats.get("activityCount")));
            row.put("searches", number(stats.get("searches")));
            row.put("views", number(stats.get("views")));
            row.put("clicks", number(stats.get("clicks")));
            row.put("lastActiveAt", stats.get("lastActiveAt"));
            return row;
        }).toList();

        return Map.of(
                "items", items,
                "page", page,
                "size", size,
                "total", total,
                "pages", total == 0 ? 0 : (total + size - 1) / size);
    }

    public Map<String, Object> conversion(int days) {
        Instant cutoff = Instant.now().minusSeconds(days * 86_400L);
        Date since = Date.from(cutoff);
        Set<String> visitors = distinct("events", "anonId", new Document("ts", new Document("$gte", since)));
        visitors.addAll(distinct("request_log", "anonId", new Document("ts", new Document("$gte", since))));

        Query newUsersQuery = Query.query(Criteria.where("createdAt").gte(
                LocalDateTime.now().minusDays(days)).and("role").ne("admin"));
        List<User> newUsers = mongo.find(newUsersQuery, User.class);
        Set<String> newUserIds = newUsers.stream().map(User::getId).collect(java.util.stream.Collectors.toSet());

        Document activeFilter = new Document("ts", new Document("$gte", since))
                .append("userId", new Document("$ne", null))
                .append("type", new Document("$in", ACTIVE_EVENTS));
        Set<String> activeUsers = distinct("events", "userId", activeFilter);
        activeUsers.addAll(distinct("request_log", "userId", new Document("ts", new Document("$gte", since))
                .append("userId", new Document("$ne", null))
                .append("path", new Document("$regex", "^/api/(wishlist|saved-searches|reviews|affiliate)"))));
        // ponytail: aggregation, not findDistinct — findDistinct runs the distinct command,
        // the same 16MB-cap / decode footgun the anonId helper already dodges. This was the
        // last distinct command in the method and the remaining 500 path.
        Set<String> registeredUsers = distinct("users", "_id", new Document("role", new Document("$ne", "admin")));
        activeUsers.retainAll(registeredUsers);
        Set<String> activatedUsers = distinct("events", "userId", new Document("ts", new Document("$gte", since))
                .append("userId", new Document("$ne", null))
                .append("type", new Document("$in", ACTIVATION_EVENTS)));
        // Source-of-truth rows cover actions created before explicit conversion events shipped.
        activatedUsers.addAll(distinct("wishlist", "userId", new Document()));
        activatedUsers.addAll(distinct("saved_searches", "userId", new Document()));
        activatedUsers.retainAll(registeredUsers);
        long activated = newUserIds.stream().filter(activatedUsers::contains).count();
        long googleSignups = newUsers.stream().filter(u -> "google".equalsIgnoreCase(u.getSignupSource())).count();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("days", days);
        result.put("totalUsers", mongo.count(Query.query(Criteria.where("role").ne("admin")), User.class));
        result.put("visitors", visitors.size());
        result.put("newUsers", newUsers.size());
        result.put("activeRegistered", activeUsers.size());
        result.put("activatedUsers", activated);
        result.put("googleSignups", googleSignups);
        result.put("emailSignups", newUsers.size() - googleSignups);
        result.put("conversionRate", percentage(newUsers.size(), visitors.size()));
        result.put("activationRate", percentage(activated, newUsers.size()));
        return result;
    }

    public Map<String, Object> detail(String id) {
        User user = users.findById(id).orElseThrow(() -> new IllegalArgumentException("User not found"));
        List<Map<String, Object>> timeline = new ArrayList<>();

        Query eventQuery = Query.query(Criteria.where("userId").is(id))
                .with(Sort.by(Sort.Direction.DESC, "ts")).limit(150);
        for (AnalyticsEvent event : mongo.find(eventQuery, AnalyticsEvent.class)) {
            timeline.add(eventActivity(event));
        }

        Query requestQuery = Query.query(Criteria.where("userId").is(id))
                .with(Sort.by(Sort.Direction.DESC, "ts")).limit(150);
        for (RequestLog request : mongo.find(requestQuery, RequestLog.class)) {
            timeline.add(requestActivity(request));
        }
        timeline.sort(Comparator.comparing(a -> (Instant) a.get("ts"), Comparator.reverseOrder()));
        if (timeline.size() > 250) timeline = new ArrayList<>(timeline.subList(0, 250));

        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("wishlist", count("wishlist", "userId", id));
        counts.put("savedSearches", count("saved_searches", "userId", id));
        counts.put("reviews", count("reviews", "userId", id));
        counts.put("reviewVotes", count("review_votes", "voterUserId", id));
        counts.put("shopClicks", count("affiliate_clicks", "userId", id));
        counts.put("events", count("events", "userId", id));
        counts.put("requests", count("request_log", "userId", id));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("user", safeUser(user));
        result.put("counts", counts);
        result.put("activity", timeline);
        return result;
    }

    private Map<String, Document> activitySummary(List<String> ids, int days) {
        if (ids.isEmpty()) return Map.of();
        Date since = Date.from(Instant.now().minusSeconds(days * 86_400L));
        List<Document> pipeline = List.of(
                new Document("$match", new Document("userId", new Document("$in", ids))
                        .append("ts", new Document("$gte", since))),
                new Document("$group", new Document("_id", "$userId")
                        .append("activityCount", new Document("$sum", 1))
                        .append("searches", sumWhen("search"))
                        .append("views", sumWhen("view"))
                        .append("clicks", sumWhen("click"))
                        .append("lastActiveAt", new Document("$max", "$ts"))));
        Map<String, Document> result = new HashMap<>();
        for (Document doc : collection("events").aggregate(pipeline)) {
            result.put(doc.getString("_id"), doc);
        }
        List<Document> requestPipeline = List.of(
                new Document("$match", new Document("userId", new Document("$in", ids))
                        .append("ts", new Document("$gte", since))),
                new Document("$group", new Document("_id", "$userId")
                        .append("requestCount", new Document("$sum", 1))
                        .append("lastActiveAt", new Document("$max", "$ts"))));
        for (Document request : collection("request_log").aggregate(requestPipeline)) {
            String id = request.getString("_id");
            Document stats = result.computeIfAbsent(id, ignored -> new Document());
            stats.put("activityCount", number(stats.get("activityCount")) + number(request.get("requestCount")));
            Object requestLast = request.get("lastActiveAt");
            Object eventLast = stats.get("lastActiveAt");
            if (eventLast == null || requestLast instanceof Date requestDate
                    && eventLast instanceof Date eventDate && requestDate.after(eventDate)) {
                stats.put("lastActiveAt", requestLast);
            }
        }
        return result;
    }

    private static Document sumWhen(String type) {
        return new Document("$sum", new Document("$cond", List.of(
                new Document("$eq", List.of("$type", type)), 1, 0)));
    }

    private Set<String> distinct(String collection, String field, Document filter) {
        // ponytail: $group aggregation, not the distinct command — distinct caps its result
        // at 16MB BSON and fails once anonId cardinality grows past it (prod traffic did).
        // Streaming cursor + allowDiskUse has no such cap; the instanceof guard skips any
        // legacy non-string value instead of throwing a decode error. Upgrade path: if even
        // the union set gets huge, count via $group+$count for the fields we only size().
        Set<String> values = new HashSet<>();
        List<Document> pipeline = List.of(
                new Document("$match", filter),
                new Document("$group", new Document("_id", "$" + field)));
        for (Document doc : collection(collection).aggregate(pipeline).allowDiskUse(true)) {
            String value = idString(doc.get("_id"));
            if (value != null && !value.isBlank()) values.add(value);
        }
        return values;
    }

    static String idString(Object value) {
        if (value instanceof String string) return string;
        if (value instanceof ObjectId objectId) return objectId.toHexString();
        return null;
    }

    private long count(String collection, String field, String id) {
        return this.collection(collection).countDocuments(new Document(field, id));
    }

    private MongoCollection<Document> collection(String name) {
        return mongo.getCollection(name);
    }

    static double percentage(long numerator, long denominator) {
        if (denominator <= 0) return 0;
        return Math.round((numerator * 10_000.0 / denominator)) / 100.0;
    }

    static Map<String, Object> safeUser(User user) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", user.getId());
        result.put("email", user.getEmail());
        result.put("username", user.getUsername());
        result.put("displayName", user.getDisplayName());
        result.put("avatarUrl", user.getAvatarUrl());
        result.put("role", user.getRole());
        result.put("emailVerified", user.getEmailVerified());
        result.put("phone", user.getPhone());
        result.put("district", user.getDistrict());
        result.put("gender", user.getGender());
        result.put("birthYear", user.getBirthYear());
        result.put("interests", user.getInterests());
        result.put("newsletterOptIn", user.getNewsletterOptIn());
        result.put("signupSource", user.getSignupSource());
        result.put("reputation", user.getReputation());
        result.put("lastLoginAt", user.getLastLoginAt());
        result.put("createdAt", user.getCreatedAt());
        result.put("updatedAt", user.getUpdatedAt());
        return result;
    }

    private static Map<String, Object> eventActivity(AnalyticsEvent event) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", event.getId());
        item.put("source", "event");
        item.put("type", event.getType());
        item.put("label", eventLabel(event));
        item.put("ts", event.getTs());
        item.put("query", event.getQuery());
        item.put("productId", event.getProductId());
        item.put("productName", event.getProductName());
        item.put("sellerSlug", event.getSellerSlug());
        item.put("path", event.getPath());
        return item;
    }

    private static String eventLabel(AnalyticsEvent event) {
        String type = event.getType() == null ? "activity" : event.getType();
        return switch (type) {
            case "signup" -> "Created an account with email";
            case "google_signup" -> "Created an account with Google";
            case "login" -> "Signed in with email";
            case "google_login" -> "Signed in with Google";
            case "email_verified" -> "Verified email address";
            case "profile_update" -> "Updated profile";
            case "search" -> "Searched for “" + value(event.getQuery(), "a product") + "”";
            case "view" -> "Viewed " + value(event.getProductName(), "a product");
            case "click" -> "Clicked through to " + value(event.getSellerSlug(), "a shop");
            case "suggest_click" -> "Selected “" + value(event.getProductName(), "a search suggestion") + "”";
            case "pageview" -> "Visited " + value(event.getPath(), "a page");
            case "member_intent_save" -> "Started saving a product";
            case "member_intent_track" -> "Started tracking a price";
            case "auth_success" -> "Completed authentication";
            case "member_action_completed_save" -> "Saved a product";
            case "member_action_completed_track" -> "Enabled a price alert";
            case "saved_search_created" -> "Saved a search";
            default -> type.replace('_', ' ');
        };
    }

    private static Map<String, Object> requestActivity(RequestLog request) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", request.getId());
        item.put("source", "request");
        item.put("type", "request");
        item.put("label", value(request.getMethod(), "REQUEST") + " " + value(request.getPath(), "/api"));
        item.put("ts", request.getTs());
        item.put("method", request.getMethod());
        item.put("path", request.getPath());
        item.put("query", request.getQuery());
        item.put("status", request.getStatus());
        item.put("latencyMs", request.getLatencyMs());
        return item;
    }

    private static String value(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static long number(Object value) {
        return value instanceof Number number ? number.longValue() : 0;
    }
}
