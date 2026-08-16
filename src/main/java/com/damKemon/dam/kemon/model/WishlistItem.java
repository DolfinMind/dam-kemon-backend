package com.damKemon.dam.kemon.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Document(collection = "wishlist")
@CompoundIndex(name = "user_product_unique", def = "{'userId': 1, 'productId': 1}", unique = true)
public class WishlistItem {

    @Id
    private String id;

    @Indexed
    private String userId;

    @Indexed
    private String productId;

    private LocalDateTime addedAt;

    /** Price at the moment the item was added — used to compute "you saved X". */
    private Double priceAtAdd;

    /**
     * User's hard target. We fire an alert as soon as Product.lowestPrice
     * crosses below this. {@code null} = no target, alert only on % drops.
     */
    private Double targetPrice;

    /**
     * Fractional drop from {@link #priceAtAdd} that triggers an alert
     * (0.10 = "tell me when it's 10% cheaper than when I added"). Defaults
     * to 0.05 server-side when the user enables alerts without picking.
     */
    private Double alertOnDropPercent;

    /** "email" / "whatsapp" / null. Email is the only one wired today. */
    private String notifyChannel;

    /** True when the user actively wants notifications. Heart-only saves stay quiet. */
    @Builder.Default
    private Boolean alertsEnabled = false;

    /** Last time we fired a notification — used to debounce repeated alerts. */
    private LocalDateTime lastNotifiedAt;

    /** Lowest price we last saw — alert only fires when current < this value. */
    private Double lastSeenLowest;
}
