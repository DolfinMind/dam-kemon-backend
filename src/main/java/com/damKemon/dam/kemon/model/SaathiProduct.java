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

/**
 * A product offered by a Saathi seller, linked to our canonical
 * {@link Product} catalog. We don't duplicate the product itself — just the
 * seller-specific bits: their price, their stock, their notes.
 *
 * <p>This is what powers the live-assist sidebar: when a seller types
 * "iphone 15" during their FB Live, we look up their SaathiProducts to
 * surface "you listed it at ৳52,500" + competitor prices from the catalog.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Document(collection = "saathi_products")
@CompoundIndex(name = "saathi_product_unique", def = "{'saathiId': 1, 'productId': 1}", unique = true)
public class SaathiProduct {

    @Id
    private String id;

    @Indexed
    private String saathiId;

    /** ID of the canonical Damkemon Product. */
    @Indexed
    private String productId;

    /** Seller's listed price (their offer). Drives the "your price vs market" diff. */
    private Double listedPrice;

    /** Optional manual note shown next to comparison (e.g. "Free delivery in Dhanmondi"). */
    private String note;

    @Builder.Default
    private Boolean inStock = true;

    private LocalDateTime addedAt;
    private LocalDateTime updatedAt;
}
