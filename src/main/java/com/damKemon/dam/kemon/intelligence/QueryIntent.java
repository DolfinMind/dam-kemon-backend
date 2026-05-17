package com.damKemon.dam.kemon.intelligence;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class QueryIntent {
    private String original;
    private String normalized;
    @Builder.Default
    private List<ProductCategory> categories = new ArrayList<>();
    @Builder.Default
    private List<String> brands = new ArrayList<>();
    @Builder.Default
    private List<String> keywords = new ArrayList<>();
    @Builder.Default
    private List<String> modelTokens = new ArrayList<>();
    private double confidence;

    public ProductCategory primaryCategory() {
        return categories.isEmpty() ? ProductCategory.GENERAL : categories.get(0);
    }

    public boolean hasCategory(ProductCategory c) {
        return categories.contains(c);
    }

    public boolean hasAnyCategory(Set<ProductCategory> set) {
        for (ProductCategory c : categories) if (set.contains(c)) return true;
        return false;
    }
}
