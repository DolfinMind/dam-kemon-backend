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
 * A "Damkemon Protect" order — a buyer's declared intent to purchase, with a
 * risk assessment attached and a lifecycle (open → confirmed | disputed). It
 * works for any seller, including off-platform Facebook shops not in our
 * catalog, which is the point: it's the trust wrapper around a transaction we
 * don't (yet) process the money for.
 *
 * <p>No payment is moved at this stage — this is the trust + dispute layer that
 * makes escrow possible later. A dispute on a known shop feeds that shop's
 * {@code ShopTrust} score, giving the verdict real teeth.
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
    private String itemName;
    private Double amount;

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
    /** "open" | "confirmed" | "disputed" | "resolved" | "cancelled". */
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
