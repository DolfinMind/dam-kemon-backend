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
    private LocalDateTime lastUpdated;
}
