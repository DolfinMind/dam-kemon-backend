package com.damKemon.dam.kemon.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * One row per inbound API request — the "every step" server-side access log.
 * Written in batches by {@link com.damKemon.dam.kemon.service.RequestLogService}
 * off the request thread, so it never adds a Mongo round-trip to the response.
 *
 * <p>Unlike {@link AnalyticsEvent} (which is deliberately no-PII), this stores
 * the RAW client IP when {@code analytics.store-raw-ip=true} so the operator can
 * see exactly who is hitting the site. It is admin-only — read solely through
 * {@code /api/admin/analytics/**}.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Document(collection = "request_log")
public class RequestLog {

    @Id
    private String id;

    private String method;

    /** Request path (no query string). */
    @Indexed
    private String path;

    /** Raw query string (truncated) so search/filter params stay visible. */
    private String query;

    private Integer status;

    private Long latencyMs;

    /** Raw client IP — only populated when {@code analytics.store-raw-ip=true}. */
    @Indexed
    private String ip;

    /** SHA-256 hash of the IP — always populated as a stable, reversible-proof key. */
    private String ipHash;

    /** Authenticated user id, when signed in. */
    @Indexed
    private String userId;

    /** Anonymous browser id from the {@code X-Anon-Id} header. */
    @Indexed
    private String anonId;

    private String userAgent;

    private String referer;

    /** Event timestamp. TTL: 30 days. */
    @Indexed(expireAfterSeconds = 60 * 60 * 24 * 30)
    private Instant ts;
}
