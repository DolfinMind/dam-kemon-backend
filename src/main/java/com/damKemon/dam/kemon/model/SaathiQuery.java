package com.damKemon.dam.kemon.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * Audit log of every Saathi lookup: live-assist sidebar queries, Messenger
 * bot auto-replies, and badge views. Used for:
 *   1. Per-seller analytics on the dashboard ("47 customers asked about
 *      iPhone 15 in the last 7 days").
 *   2. Billing aggregation for usage-based tiers.
 *   3. Trust signals — sellers who get a steady stream of real queries
 *      earn priority placement.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Document(collection = "saathi_queries")
@CompoundIndex(name = "saathi_ts_idx", def = "{'saathiId': 1, 'ts': -1}")
public class SaathiQuery {

    @Id
    private String id;

    @Indexed
    private String saathiId;

    /** "live_assist" | "messenger" | "badge_view" | "public_profile" */
    private String source;

    private String rawQuery;
    private String normalizedQuery;

    /** Was a product in OUR catalog matched? If yes, the productId. */
    private String matchedProductId;

    /** Asker identity, when known. Anonymous queries are common. */
    private String fromAnonId;
    private String fromMessengerPsid;   // FB page-scoped user ID
    private String fromIp;

    /** Convenience field — what the asker received as a reply. Truncated. */
    private String replyPreview;

    @Indexed
    private Instant ts;
}
