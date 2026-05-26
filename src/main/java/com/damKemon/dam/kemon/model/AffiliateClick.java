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
 * One row per outbound click through {@code /api/r/{productId}}. We use
 * these for two things:
 *   <li>Reconciling affiliate commissions: monthly join with the partner
 *       network report by {@code clickId} (the value we stuff into their
 *       tracking parameter).</li>
 *   <li>Health monitoring: which shops are converting, which categories
 *       drive the most outbound traffic — surfaced in the operator
 *       dashboard.</li>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Document(collection = "affiliate_clicks")
@CompoundIndex(name = "site_day_idx", def = "{'siteSlug': 1, 'ts': -1}")
public class AffiliateClick {

    @Id
    private String id;

    @Indexed
    private String productId;

    @Indexed
    private String siteSlug;

    private String userId;
    private String anonId;
    private String clickId;     // value passed to the partner network for reconciliation
    private String fromQuery;   // search query that led here (for attribution analytics)
    private String referer;
    private String userAgent;
    private String ip;
    private String outboundUrl; // final URL we 302'd to (incl. ref code)

    @Indexed
    private Instant ts;
}
