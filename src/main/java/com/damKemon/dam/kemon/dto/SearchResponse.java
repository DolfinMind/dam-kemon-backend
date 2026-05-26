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

    /**
     * Set when the literal regex/text passes returned nothing usable and we
     * fell back to fuzzy matching — e.g. user typed "ipone 15" and we
     * served "iPhone 15 Pro Max" via trigram similarity. Frontend renders a
     * "Did you mean …?" suggestion above the results.
     */
    private String didYouMean;

    /**
     * IDs of products in {@link #products} that are paid placements. Frontend
     * renders a "Sponsored" chip on these. We surface IDs (not a separate
     * list) so the existing sort/filter UI keeps working unchanged.
     */
    @Builder.Default
    private List<String> sponsoredProductIds = new ArrayList<>();
}
