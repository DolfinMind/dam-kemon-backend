package com.damKemon.dam.kemon.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

/**
 * Per-shop trust, delivery and genuineness profile — the data behind the
 * "beyond price" decision layer. A buyer doesn't only want the cheapest
 * seller; they want to know whether the seller is trustworthy, whether the
 * product is genuine, how long delivery takes, and how painful returns are.
 *
 * <p>Two layers feed each row, mirroring how the shop catalog itself works:
 * <ul>
 *   <li><b>Curated baseline</b> — seeded from {@code resources/shop-trust.json}
 *       at boot (see {@code ShopTrustBootstrap}). These are the editorial
 *       facts: official-vs-marketplace, typical delivery window, COD, return
 *       policy, warranty. They make the layer useful on day one.</li>
 *   <li><b>Community aggregates</b> — updated every time a shopper submits a
 *       review on a product sold by this shop. Star ratings, "do you trust
 *       this seller" votes, would-recommend, and reported delivery days all
 *       nudge {@link #computedTrust} over time.</li>
 * </ul>
 *
 * Tracked by {@link #shopSlug} (unique) — the same slug carried on
 * {@code SitePrice.siteSlug}, so the frontend can join a product's sellers
 * to their trust profiles in one batch call.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Document(collection = "shop_trust")
public class ShopTrust {

    @Id
    private String id;

    @Indexed(unique = true)
    private String shopSlug;

    private String shopName;

    // ─── Curated baseline (shop-trust.json) ───

    /** Starting reputation 0..100 before any community input. */
    private Integer baseTrust;

    /** Typical delivery window in days (Dhaka metro reference). */
    private Integer deliveryDaysMin;
    private Integer deliveryDaysMax;

    /** Cash-on-delivery offered. */
    private Boolean codAvailable;

    /** Return window in days; 0 means no returns accepted. */
    private Integer returnWindowDays;

    /** How painful returns are in practice: "easy" | "limited" | "none". */
    private String returnEase;

    /**
     * Genuineness signal:
     * "authorized"     — brand-authorized retailer (official warranty)
     * "official_store" — the brand's own storefront
     * "marketplace"    — mixed third-party sellers; genuineness varies by seller
     * "reseller"       — independent retailer, typically genuine
     * "unknown"        — not yet classified
     */
    private String authenticity;

    /** Free-text warranty summary, e.g. "Official 1-year". */
    private String warranty;

    /** Seller responsiveness: "fast" | "normal" | "slow". */
    private String responseTime;

    // ─── Community aggregates (updated as reviews arrive) ───

    @Builder.Default private Integer ratingCount = 0;
    /** Sum of 1..5 star ratings; average = ratingSum / ratingCount. */
    @Builder.Default private Double ratingSum = 0.0;

    /** "I trust this seller" vs "I don't" tallies. */
    @Builder.Default private Integer trustUp = 0;
    @Builder.Default private Integer trustDown = 0;

    @Builder.Default private Integer recommendYes = 0;
    @Builder.Default private Integer recommendNo = 0;

    /** Reported delivery experiences; average = deliveryDaysSum / deliveryReports. */
    @Builder.Default private Integer deliveryReports = 0;
    @Builder.Default private Double deliveryDaysSum = 0.0;

    // ─── Derived ───

    /** Final 0..100 score shown to users (baseline blended with community). */
    private Integer computedTrust;

    private LocalDateTime updatedAt;
}
