package com.damKemon.dam.kemon.dto;

import com.damKemon.dam.kemon.model.Product;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SearchResponse {
    private String query;
    private List<Product> products;
    /** Full number of ranked matches for the query (NOT just this page). */
    private Integer totalResults;
    private List<String> sitesSearched;

    /** Zero-based page index of {@link #products} within the full ranked set. */
    private Integer page;
    /** Page size requested. */
    private Integer size;
    /** True when more ranked results exist beyond this page (drives "Load more"). */
    private Boolean hasMore;

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

    /**
     * Variant spec facets computed over the ranked matches (item 3): for a
     * phones/computing query, the available RAM / Storage / Display values + their
     * counts, so the UI can offer variant filters. Shape:
     * {@code { "RAM": [ {value:"8GB", count:12}, … ], "Storage": [ … ] } }.
     * Empty when the matched products carry no parseable specs.
     */
    @Builder.Default
    private Map<String, List<Map<String, Object>>> facets = new LinkedHashMap<>();
}
