package com.damKemon.dam.kemon.dto;

import com.damKemon.dam.kemon.model.Product;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SearchResponse {
    private String query;
    private List<Product> products;
    private Integer totalResults;
    private List<String> sitesSearched;

    /** Intent metadata: detected categories, brands, confidence. */
    private String detectedCategory;
    private List<String> categories;
    private List<String> brands;
    private Double confidence;

    @Builder.Default
    private List<String> sitesSkipped = new ArrayList<>();
}
