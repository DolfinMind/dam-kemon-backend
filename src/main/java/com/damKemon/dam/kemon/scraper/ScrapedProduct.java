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
}
