package com.damKemon.dam.kemon.service;

import com.damKemon.dam.kemon.model.AnalyticsEvent;
import com.damKemon.dam.kemon.repository.AnalyticsEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

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

    public AnalyticsService(AnalyticsEventRepository repo) {
        this.repo = repo;
    }

    @Async
    public void recordSearch(String query, int resultCount, String anonId, String ip) {
        recordSearch(query, resultCount, anonId, ip, null, null);
    }

    @Async
    public void recordSearch(String query, int resultCount, String anonId, String ip,
                             String userId, Long latencyMs) {
        if (query == null) return;
        String q = query.trim();
        if (q.isEmpty()) return;
        if (q.length() > MAX_QUERY_LEN) q = q.substring(0, MAX_QUERY_LEN);
        save(AnalyticsEvent.builder()
                .type("search")
                .query(q.toLowerCase())
                .resultCount(resultCount)
                .anonId(safe(anonId))
                .userId(safe(userId))
                .latencyMs(latencyMs)
                .ipHash(hashIp(ip))
                .ts(Instant.now())
                .build());
    }

    @Async
    public void recordView(String productId, String anonId, String ip) {
        if (productId == null || productId.isBlank()) return;
        save(AnalyticsEvent.builder()
                .type("view")
                .productId(productId)
                .anonId(safe(anonId))
                .ipHash(hashIp(ip))
                .ts(Instant.now())
                .build());
    }

    @Async
    public void recordClick(String productId, String sellerSlug, String anonId, String ip) {
        if (productId == null && sellerSlug == null) return;
        save(AnalyticsEvent.builder()
                .type("click")
                .productId(productId)
                .sellerSlug(sellerSlug)
                .anonId(safe(anonId))
                .ipHash(hashIp(ip))
                .ts(Instant.now())
                .build());
    }

    private void save(AnalyticsEvent e) {
        try { repo.save(e); }
        catch (DataAccessException ex) { log.debug("analytics save dropped: {}", ex.getMessage()); }
        catch (Exception ex) { log.debug("analytics unexpected: {}", ex.getMessage()); }
    }

    private static String safe(String s) {
        if (s == null) return null;
        s = s.trim();
        return s.length() > 64 ? s.substring(0, 64) : s;
    }

    /** SHA-256 hex of the IP, truncated. Not reversible without the IP. */
    private static String hashIp(String ip) {
        if (ip == null || ip.isBlank()) return null;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(ip.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(h).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            return null;
        }
    }
}
