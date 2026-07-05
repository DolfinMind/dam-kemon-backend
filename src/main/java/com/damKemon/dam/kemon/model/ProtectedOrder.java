package com.damKemon.dam.kemon.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * A "Damkemon Protect" row — either a logged purchase (lifecycle
 * open → confirmed | disputed) or a standalone scam report (status
 * "reported"). Works for any seller, including off-platform Facebook pages
 * and bKash numbers not in our catalog — together these rows form the
 * crowdsourced scam registry that {@code ProtectService.assessRisk} answers
 * from.
 *
 * <p>No payment moves through us. The teeth: a confirm/dispute/report on a
 * known shop feeds that shop's {@code ShopTrust} score via
 * {@code TrustService.applyReview}.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Document(collection = "protected_orders")
public class ProtectedOrder {

    @Id
    private String id;

    /** Short, shareable, human-friendly code e.g. "DK-7F3K2A". */
    @Indexed(unique = true)
    private String protectionCode;

    /** Anonymous browser id of the buyer (best-effort ownership). */
    @Indexed
    private String anonId;

    // ─── what's being bought ───
    private String productId;   // optional — set when bought via our catalog
    private String shopSlug;    // optional — set when the seller is a known shop
    private String sellerName;  // free text (e.g. a Facebook page name)
    @Indexed
    private String sellerIdentifier; // URL or Phone number from Trust Vault query
    private String itemName;
    private Double amount;

    /** "known_shop" | "website" | "fb_page" | "instagram" | "marketplace" | "unknown". */
    private String sellerType;
    /** Detected/declared product category tag (e.g. "smartphones"). */
    private String category;

    /**
     * "cod" | "bkash_personal" | "bkash_merchant" | "nagad_personal" |
     * "nagad_merchant" | "card" | "advance_bank" | "other".
     */
    private String paymentMethod;

    // ─── risk snapshot at creation ───
    private Integer riskScore;        // 0 (safe) .. 100 (very risky)
    private String riskLevel;         // "low" | "medium" | "high"
    @Builder.Default
    private List<String> riskFlags = new ArrayList<>();

    // ─── lifecycle ───
    /** "open" | "confirmed" | "disputed" | "reported" | "resolved" | "cancelled".
     *  "reported" = a standalone scam report with no prior logged purchase. */
    @Builder.Default
    private String status = "open";
    private String buyerNote;
    private String disputeReason;
    private LocalDateTime deliveryDeadline;

    @Builder.Default
    private List<Event> timeline = new ArrayList<>();

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class Event {
        private LocalDateTime at;
        private String type;   // "created" | "confirmed" | "disputed" | "resolved"
        private String note;
    }
}
