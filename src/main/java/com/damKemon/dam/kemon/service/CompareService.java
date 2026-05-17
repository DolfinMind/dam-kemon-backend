package com.damKemon.dam.kemon.service;

import com.damKemon.dam.kemon.dto.CompareResponse;
import com.damKemon.dam.kemon.dto.CompareResponse.AttributeRow;
import com.damKemon.dam.kemon.model.Product;
import com.damKemon.dam.kemon.model.SitePrice;
import com.damKemon.dam.kemon.repository.ProductRepository;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class CompareService {

    private final ProductRepository productRepository;

    public CompareService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    public CompareResponse compare(List<String> ids) {
        List<Product> products = new ArrayList<>();
        for (String id : ids) {
            productRepository.findById(id).ifPresent(products::add);
        }
        return build(products);
    }

    private CompareResponse build(List<Product> products) {
        if (products.isEmpty()) {
            return CompareResponse.builder()
                    .products(products)
                    .attributes(Collections.emptyList())
                    .bestIndexByAttribute(Collections.emptyMap())
                    .build();
        }

        List<AttributeRow> rows = new ArrayList<>();
        Map<String, Integer> best = new LinkedHashMap<>();

        // --- core comparison attributes ---
        rows.add(numericRow(products, "lowestPrice",  "Lowest price",  "৳", false, p -> p.getLowestPrice()));
        rows.add(numericRow(products, "highestPrice", "Highest price", "৳", false, p -> p.getHighestPrice()));
        rows.add(numericRow(products, "averageRating","Avg rating",    "★", true,  p -> p.getAverageRating()));
        rows.add(numericRow(products, "totalReviews", "Reviews",       null, true, p -> p.getTotalReviews() == null ? null : p.getTotalReviews().doubleValue()));
        rows.add(numericRow(products, "sellerCount",  "Sellers tracked", null, true,
                p -> p.getPrices() == null ? null : (double) p.getPrices().size()));
        rows.add(textRow(products, "category", "Category", p -> p.getCategory()));
        rows.add(numericRow(products, "savings", "Potential savings", "৳", true,
                p -> (p.getLowestPrice() == null || p.getHighestPrice() == null) ? null
                        : Math.max(0, p.getHighestPrice() - p.getLowestPrice())));
        rows.add(numericRow(products, "discount", "Best discount", "%", true,
                p -> bestDiscount(p)));
        rows.add(booleanRow(products, "anyInStock", "Available", true,
                p -> p.getPrices() != null && p.getPrices().stream().anyMatch(sp -> Boolean.TRUE.equals(sp.getInStock()))));

        // populate best-index map
        for (AttributeRow row : rows) {
            Integer winnerIdx = pickBest(row);
            if (winnerIdx != null) best.put(row.getKey(), winnerIdx);
        }

        return CompareResponse.builder()
                .products(products)
                .attributes(rows)
                .bestIndexByAttribute(best)
                .build();
    }

    // ---------- row builders ----------
    private AttributeRow numericRow(List<Product> products, String key, String label,
                                     String unit, boolean higherBetter,
                                     java.util.function.Function<Product, Double> getter) {
        List<Object> vals = new ArrayList<>();
        for (Product p : products) vals.add(getter.apply(p));
        return AttributeRow.builder()
                .key(key).label(label).type("number")
                .values(vals).unit(unit).higherIsBetter(higherBetter)
                .build();
    }

    private AttributeRow textRow(List<Product> products, String key, String label,
                                  java.util.function.Function<Product, String> getter) {
        List<Object> vals = new ArrayList<>();
        for (Product p : products) vals.add(getter.apply(p));
        return AttributeRow.builder().key(key).label(label).type("text").values(vals).build();
    }

    private AttributeRow booleanRow(List<Product> products, String key, String label,
                                     boolean higherBetter,
                                     java.util.function.Function<Product, Boolean> getter) {
        List<Object> vals = new ArrayList<>();
        for (Product p : products) vals.add(getter.apply(p));
        return AttributeRow.builder()
                .key(key).label(label).type("boolean")
                .values(vals).higherIsBetter(higherBetter)
                .build();
    }

    private Integer pickBest(AttributeRow row) {
        if (row.getValues() == null || row.getValues().isEmpty()) return null;
        if ("text".equals(row.getType())) return null;

        Boolean higher = row.getHigherIsBetter();
        if (higher == null) return null;

        Integer bestIdx = null;
        double bestVal = higher ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
        for (int i = 0; i < row.getValues().size(); i++) {
            Object v = row.getValues().get(i);
            Double dv = toDouble(v);
            if (dv == null) continue;
            boolean better = higher ? dv > bestVal : dv < bestVal;
            if (better) { bestVal = dv; bestIdx = i; }
        }
        return bestIdx;
    }

    private Double toDouble(Object o) {
        if (o == null) return null;
        if (o instanceof Number) return ((Number) o).doubleValue();
        if (o instanceof Boolean) return ((Boolean) o) ? 1.0 : 0.0;
        try { return Double.parseDouble(o.toString()); } catch (Exception e) { return null; }
    }

    private Double bestDiscount(Product p) {
        if (p.getPrices() == null) return null;
        double max = 0;
        boolean found = false;
        for (SitePrice sp : p.getPrices()) {
            if (sp.getDiscount() != null) {
                if (sp.getDiscount() > max) max = sp.getDiscount();
                found = true;
            }
        }
        return found ? max : null;
    }
}
