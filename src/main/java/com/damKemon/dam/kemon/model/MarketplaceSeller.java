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
 * Reputation profile for a marketplace sub-seller (e.g. an individual Daraz
 * storefront like "MR GAMER SHOP"), computed entirely from REAL signals we
 * scrape per listing: the listing's rating, its review count, and units sold.
 *
 * <p>Unlike {@link ShopTrust} — which describes a whole shop/marketplace and
 * carries curated facts (delivery window, COD, returns, genuineness) — this is
 * a derived, data-only score that distinguishes one Daraz seller from another.
 * Delivery/COD/returns still come from the marketplace's {@link ShopTrust}; this
 * adds the per-seller dimension the marketplace level can't.
 *
 * <p>Keyed by {@link #sellerId} (the marketplace's own seller id, carried on
 * {@code SitePrice.sellerId}), so the frontend joins a product's offers to their
 * seller reputations in one batch call.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Document(collection = "marketplace_sellers")
public class MarketplaceSeller {

    @Id
    private String id;

    /** Marketplace's own seller id, e.g. Daraz sellerId. */
    @Indexed(unique = true)
    private String sellerId;

    private String sellerName;

    /** The marketplace this seller trades on (shop slug, e.g. "daraz"). */
    private String marketplace;

    /** How many of this seller's listings we currently index. */
    private Integer listingCount;

    /** Mean of listing ratings that have one (1..5), or null if none are rated. */
    private Double ratingAvg;

    /** Total reviews across this seller's listings — the confidence signal. */
    private Integer reviewTotal;

    /** Total units sold across this seller's listings — the volume signal. */
    private Long soldTotal;

    /** Derived reputation, 0..100. */
    private Integer trustScore;

    private LocalDateTime updatedAt;
}
