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
 * Anonymous user event — search hits, product views, outbound seller clicks.
 *
 * <p>No PII is stored. {@code anonId} is a UUID minted in {@code localStorage}
 * on the client and lasts for that browser only; we treat it as best-effort
 * uniqueness, not identity.
 *
 * <p>The collection grows ~1 doc per search and 1 per product view. We rely
 * on a {@link org.springframework.data.mongodb.core.index.Indexed#expireAfterSeconds}
 * TTL on {@code ts} to keep the collection bounded — older history rolls into
 * aggregate counters before it expires.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Document(collection = "events")
public class AnalyticsEvent {

    @Id
    private String id;

    /** "search" | "view" | "click" */
    @Indexed
    private String type;

    /** Search term (only set for type=search). Lower-cased + trimmed. */
    @Indexed
    private String query;

    /** Count of products returned (only set for type=search). */
    private Integer resultCount;

    /** Product id (set for type=view and type=click). */
    @Indexed
    private String productId;

    /** Shop slug the user clicked through to (set for type=click). */
    @Indexed
    private String sellerSlug;

    /** Anon client id from localStorage UUID. Never tied to PII. */
    @Indexed
    private String anonId;

    /** Authenticated user id, populated only for signed-in users. */
    @Indexed
    private String userId;

    /** Search latency in milliseconds (only set for type=search). */
    private Long latencyMs;

    /** Rough hash of the client IP, only used for rate-limiter audit. */
    private String ipHash;

    /** Event timestamp. TTL: 30 days. */
    @Indexed(expireAfterSeconds = 60 * 60 * 24 * 30)
    private Instant ts;
}
