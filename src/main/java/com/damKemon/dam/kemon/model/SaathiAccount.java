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
 * A Damkemon Saathi merchant — a Facebook-commerce seller who signed up for
 * the SaaS toolkit. Distinct from {@link Seller} (the admin-curated public
 * directory) because Saathi is self-service, billable, and gates access to
 * the live-assist sidebar and Messenger bot.
 *
 * <p>One-to-one with {@link User} via {@link #userId}. When a Saathi account
 * is verified we also upsert a {@link Seller} row (source="saathi") so the
 * verified shop appears in the public directory.
 *
 * <h3>Verification states</h3>
 * <ul>
 *   <li>{@code pending} — signed up, paperwork not reviewed yet.</li>
 *   <li>{@code verified} — admin approved, badge active.</li>
 *   <li>{@code rejected} — admin saw red flags; reason in {@link #verificationNote}.</li>
 *   <li>{@code suspended} — was verified, lost trust (refund complaints, fake products).</li>
 * </ul>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Document(collection = "saathi_accounts")
public class SaathiAccount {

    @Id
    private String id;

    /** The Damkemon user account behind this Saathi. */
    @Indexed(unique = true)
    private String userId;

    /**
     * Public slug — used in {@code /api/saathi/p/{slug}} and the embed badge
     * URL. Derived from the FB page name; must be unique across the platform.
     */
    @Indexed(unique = true)
    private String slug;

    /** Displayed on the storefront. Defaults to the FB page name at signup. */
    private String displayName;

    private String facebookUrl;
    private String messengerUrl;
    private String whatsapp;          // BD sellers do most ops via WhatsApp + Messenger
    private String pickupAddress;     // for "visit our shop" CTA
    private String city;
    private String area;

    /**
     * Primary product categories the seller deals in. Used for matching to
     * inbound buyer queries — when a buyer asks for "iPhone 15", we only
     * surface Saathi sellers whose categories include smartphones.
     */
    @Builder.Default
    private java.util.List<String> categories = new java.util.ArrayList<>();

    /** "pending" / "verified" / "rejected" / "suspended" */
    @Indexed
    @Builder.Default
    private String verificationStatus = "pending";

    /** Note explaining a reject/suspend decision. Visible to seller. */
    private String verificationNote;
    private LocalDateTime verifiedAt;
    private LocalDateTime suspendedAt;

    /**
     * What the seller submitted as proof: NID number (hashed), trade
     * license number, business registration. Never store raw NID.
     */
    private String nidHash;
    private String tradeLicense;

    /** "lite" / "pro" / "enterprise" — null while on trial. */
    private String tier;

    @Builder.Default
    private LocalDateTime trialUntil = LocalDateTime.now().plusDays(14);

    /** Last day the seller is paid through. {@code null} = trial or expired. */
    private LocalDateTime paidUntil;

    /**
     * Aggregate fairness signals used for ranking & trust. Updated by a
     * scheduler off the {@code saathi_queries} + refund events.
     */
    @Builder.Default
    private Long totalQueries = 0L;
    @Builder.Default
    private Long totalSales = 0L;
    private Double rating;        // 0-5, averaged over user feedback
    private Integer ratingCount;
    private String avgReplyTime;  // "5 min" / "1 hour"

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
