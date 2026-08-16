package com.damKemon.dam.kemon.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SitePrice {
    private String siteName;
    private String siteSlug;
    private String productUrl;
    private Double price;
    private Double originalPrice;
    private Double discount;
    @Builder.Default
    private String currency = "BDT";
    private Boolean inStock;
    private Double rating;
    private Integer reviewCount;
    // Marketplace sub-seller within siteName/siteSlug (e.g. a Daraz storefront).
    // Null for first-party shops, where the shop itself is the seller. Lets one
    // product carry many sellers from the same marketplace, each its own offer.
    private String sellerName;
    private String sellerId;
    private Integer soldCount;
    private LocalDateTime lastUpdated;

    /** Wire-only: identity stripped for anonymous callers — price stays, the
     *  shop behind it is the signup carrot. Never persisted. */
    @org.springframework.data.annotation.Transient
    private Boolean locked;
}
