package com.damKemon.dam.kemon.service;

import com.damKemon.dam.kemon.model.AnalyticsEvent;
import com.damKemon.dam.kemon.repository.AnalyticsEventRepository;
import com.damKemon.dam.kemon.util.ClientIp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Fire-and-forget event recorder. Every call is {@code @Async} so we don't
 * add a Mongo round-trip to the user-facing request path.
 *
 * <p>The service is intentionally forgiving — if Mongo is briefly unreachable
 * an event is dropped rather than surfaced to the user. The catalog search
 * path matters more than perfect analytics fidelity.
 */
@Service
public class AnalyticsService {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsService.class);
    private static final int MAX_QUERY_LEN = 120;

    private final AnalyticsEventRepository repo;

    /** When false, only the IP hash is kept (the original no-PII behaviour). */
    private final boolean storeRawIp;

    public AnalyticsService(AnalyticsEventRepository repo,
                            @Value("${analytics.store-raw-ip:true}") boolean storeRawIp) {
        this.repo = repo;
        this.storeRawIp = storeRawIp;
    }

    @Async
    public void recordSearch(String query, int resultCount, String anonId, String ip) {
        recordSearch(query, resultCount, anonId, ip, null, null, null);
    }

    @Async
    public void recordSearch(String query, int resultCount, String anonId, String ip,
                             String userId, Long latencyMs, java.util.List<String> resultShops) {
        if (query == null) return;
        String q = query.trim();
        if (q.isEmpty()) return;
        if (q.length() > MAX_QUERY_LEN) q = q.substring(0, MAX_QUERY_LEN);
        save(AnalyticsEvent.builder()
                .type("search")
                .query(q.toLowerCase())
                .resultCount(resultCount)
                .resultShops(resultShops == null || resultShops.isEmpty() ? null : resultShops)
                .anonId(safe(anonId))
                .userId(safe(userId))
                .latencyMs(latencyMs)
                .ip(rawIp(ip))
                .ipHash(ClientIp.hash(ip))
                .ts(Instant.now())
                .build());
    }

    @Async
    public void recordView(String productId, String anonId, String ip) {
        recordView(productId, anonId, ip, null);
    }

    @Async
    public void recordView(String productId, String anonId, String ip, String userId) {
        if (productId == null || productId.isBlank()) return;
        save(AnalyticsEvent.builder()
                .type("view")
                .productId(productId)
                .anonId(safe(anonId))
                .userId(safe(userId))
                .ip(rawIp(ip))
                .ipHash(ClientIp.hash(ip))
                .ts(Instant.now())
                .build());
    }

    @Async
    public void recordClick(String productId, String sellerSlug, String anonId, String ip) {
        recordClick(productId, sellerSlug, anonId, ip, null);
    }

    @Async
    public void recordClick(String productId, String sellerSlug, String anonId, String ip, String userId) {
        if (productId == null && sellerSlug == null) return;
        save(AnalyticsEvent.builder()
                .type("click")
                .productId(productId)
                .sellerSlug(sellerSlug)
                .anonId(safe(anonId))
                .userId(safe(userId))
                .ip(rawIp(ip))
                .ipHash(ClientIp.hash(ip))
                .ts(Instant.now())
                .build());
    }

    /** Autosuggest dropdown click: the user typed {@code query} and picked
     *  {@code productName} — the "searched X, chose Y" pair the search log shows. */
    @Async
    public void recordSuggestClick(String query, String productId, String productName,
                                   String anonId, String ip) {
        recordSuggestClick(query, productId, productName, anonId, ip, null);
    }

    @Async
    public void recordSuggestClick(String query, String productId, String productName,
                                   String anonId, String ip, String userId) {
        if (productId == null && productName == null) return;
        String q = query == null ? null : query.trim().toLowerCase();
        if (q != null && q.length() > MAX_QUERY_LEN) q = q.substring(0, MAX_QUERY_LEN);
        save(AnalyticsEvent.builder()
                .type("suggest_click")
                .query(q == null || q.isEmpty() ? null : q)
                .productId(safe(productId))
                .productName(safe256(productName))
                .anonId(safe(anonId))
                .userId(safe(userId))
                .ip(rawIp(ip))
                .ipHash(ClientIp.hash(ip))
                .ts(Instant.now())
                .build());
    }

    /** A single SPA page navigation. Captures the route, referrer and device. */
    @Async
    public void recordPageView(String path, String anonId, String ip,
                               String userId, String referer, String userAgent) {
        if (path == null || path.isBlank()) return;
        String p = path.trim();
        if (p.length() > MAX_QUERY_LEN) p = p.substring(0, MAX_QUERY_LEN);
        save(AnalyticsEvent.builder()
                .type("pageview")
                .path(p)
                .anonId(safe(anonId))
                .userId(safe(userId))
                .referer(safe256(referer))
                .userAgent(safe256(userAgent))
                .ip(rawIp(ip))
                .ipHash(ClientIp.hash(ip))
                .ts(Instant.now())
                .build());
    }

    /** Explicit account lifecycle events that cannot be inferred from authenticated requests. */
    @Async
    public void recordAccountActivity(String type, String userId) {
        if (type == null || userId == null) return;
        save(AnalyticsEvent.builder()
                .type(safe(type))
                .userId(safe(userId))
                .ts(Instant.now())
                .build());
    }

    /** Explicit conversion action; endpoint-level allowlisting keeps type cardinality bounded. */
    @Async
    public void recordAction(String type, String productId, String anonId, String ip, String userId) {
        save(AnalyticsEvent.builder()
                .type(safe(type))
                .productId(safe(productId))
                .anonId(safe(anonId))
                .userId(safe(userId))
                .ip(rawIp(ip))
                .ipHash(ClientIp.hash(ip))
                .ts(Instant.now())
                .build());
    }

    private void save(AnalyticsEvent e) {
        try { repo.save(e); }
        catch (DataAccessException ex) { log.debug("analytics save dropped: {}", ex.getMessage()); }
        catch (Exception ex) { log.debug("analytics unexpected: {}", ex.getMessage()); }
    }

    /** Raw IP only when the operator has opted in; otherwise null (hash still kept). */
    private String rawIp(String ip) {
        return storeRawIp ? ip : null;
    }

    private static String safe(String s) {
        if (s == null) return null;
        s = s.trim();
        return s.length() > 64 ? s.substring(0, 64) : s;
    }

    private static String safe256(String s) {
        if (s == null) return null;
        s = s.trim();
        if (s.isEmpty()) return null;
        return s.length() > 256 ? s.substring(0, 256) : s;
    }
}
