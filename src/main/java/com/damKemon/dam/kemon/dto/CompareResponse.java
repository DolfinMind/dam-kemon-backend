package com.damKemon.dam.kemon.dto;

import com.damKemon.dam.kemon.model.Product;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Side-by-side product comparison.
 *   - products: list of full Product objects in the order requested
 *   - attributes: per-attribute row, e.g.,
 *       { key: "lowestPrice",  label: "Lowest price",  values: [120000, 130000] }
 *   - bestIndexByAttribute: which product wins each attribute
 *       e.g., { "lowestPrice": 0, "averageRating": 1 }
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class CompareResponse {
    private List<Product> products;
    private List<AttributeRow> attributes;
    private Map<String, Integer> bestIndexByAttribute;

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class AttributeRow {
        private String key;
        private String label;
        private String type;          // "price" | "rating" | "number" | "text" | "boolean"
        private List<Object> values;  // per-product values, same order as products
        private String unit;          // optional, e.g., "৳", "★"
        private Boolean higherIsBetter;
    }
}
