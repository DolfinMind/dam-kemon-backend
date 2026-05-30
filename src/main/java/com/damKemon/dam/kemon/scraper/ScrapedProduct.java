package com.damKemon.dam.kemon.scraper;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ScrapedProduct {
    private String name;
    private Double price;
    private Double originalPrice;
    private String productUrl;
    private String imageUrl;
    private Double rating;
    private Integer reviewCount;
    private Boolean inStock;

    // Marketplace sub-seller (e.g. a Daraz storefront). Null for first-party
    // shops, where the shop itself is the seller. When present, the indexer
    // treats this as a distinct offer so one product can list many sellers.
    private String sellerName;
    private String sellerId;
    private Integer soldCount;
}
