package com.damKemon.dam.kemon.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Document(collection = "products")
public class Product {
    @Id
    private String id;
    private String name;
    private String slug;
    private String category;
    private String imageUrl;
    private String description;
    @Builder.Default
    private List<SitePrice> prices = new ArrayList<>();
    private Double lowestPrice;
    private Double highestPrice;
    private Double averageRating;
    private Integer totalReviews;
    private LocalDateTime lastScraped;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
