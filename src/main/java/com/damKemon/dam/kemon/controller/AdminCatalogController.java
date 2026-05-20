package com.damKemon.dam.kemon.controller;

import com.damKemon.dam.kemon.model.Product;
import com.damKemon.dam.kemon.model.SitePrice;
import com.damKemon.dam.kemon.repository.ProductRepository;
import com.damKemon.dam.kemon.service.ProductService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Catalog editor for operators. List + search products, click into one to
 * rename / re-categorise / fix image URL, merge two duplicates into one,
 * or flag spam (soft delete = move out of {@code products}).
 */
@RestController
@RequestMapping("/api/admin/catalog")
public class AdminCatalogController {

    private static final Logger log = LoggerFactory.getLogger(AdminCatalogController.class);

    private final ProductRepository products;
    private final ProductService productService;
    private final MongoTemplate mongo;

    public AdminCatalogController(ProductRepository products,
                                  ProductService productService,
                                  MongoTemplate mongo) {
        this.products = products;
        this.productService = productService;
        this.mongo = mongo;
    }

    /**
     * Paginated catalog browser. Supports a free-text {@code q} (regex on
     * name) and a {@code category} filter.
     */
    @GetMapping
    public ResponseEntity<?> list(
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "category", required = false) String category,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "30") int size) {
        try {
            Query query = new Query().with(PageRequest.of(page, Math.min(size, 100)));
            if (q != null && !q.isBlank()) {
                query.addCriteria(Criteria.where("name").regex(java.util.regex.Pattern.quote(q), "i"));
            }
            if (category != null && !category.isBlank()) {
                query.addCriteria(Criteria.where("category").is(category));
            }
            List<Product> rows = mongo.find(query, Product.class);
            long total = mongo.count(Query.of(query).limit(-1).skip(-1), Product.class);
            return ResponseEntity.ok(Map.of(
                    "content", rows,
                    "page", page,
                    "size", size,
                    "totalElements", total
            ));
        } catch (DataAccessException e) {
            return ResponseEntity.ok(Map.of("content", List.of(), "totalElements", 0));
        }
    }

    /** Edit core fields of a product. Operator override of indexer detection. */
    @PatchMapping("/{id}")
    public ResponseEntity<?> editProduct(@PathVariable String id, @RequestBody Map<String, Object> body) {
        try {
            Product p = products.findById(id).orElse(null);
            if (p == null) return ResponseEntity.notFound().build();
            if (body.containsKey("name")) p.setName(String.valueOf(body.get("name")));
            if (body.containsKey("category")) p.setCategory(String.valueOf(body.get("category")));
            if (body.containsKey("description")) p.setDescription(String.valueOf(body.get("description")));
            if (body.containsKey("imageUrl")) p.setImageUrl(String.valueOf(body.get("imageUrl")));
            if (body.containsKey("brands") && body.get("brands") instanceof List<?> b) {
                p.setBrands(b.stream().map(String::valueOf).toList());
            }
            p.setUpdatedAt(LocalDateTime.now());
            products.save(p);
            return ResponseEntity.ok(p);
        } catch (DataAccessException e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Flag as spam — soft delete by moving the product out of the live
     * catalog. We just delete; a future enhancement could move to a
     * {@code spam} collection so we can rebuild from it.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteProduct(@PathVariable String id) {
        try {
            if (!products.existsById(id)) return ResponseEntity.notFound().build();
            products.deleteById(id);
            log.info("AdminCatalog: deleted product {}", id);
            return ResponseEntity.noContent().build();
        } catch (DataAccessException e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Merge {@code from} into {@code to}: every {@link SitePrice} from
     * {@code from} is appended to {@code to}, the lower lowest-price wins,
     * descriptive fields fill in if missing, and {@code from} is deleted.
     */
    @PostMapping("/{toId}/merge")
    public ResponseEntity<?> merge(@PathVariable String toId, @RequestBody Map<String, String> body) {
        String fromId = body.get("from");
        if (fromId == null || fromId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "from is required"));
        }
        if (fromId.equals(toId)) {
            return ResponseEntity.badRequest().body(Map.of("error", "cannot merge a product into itself"));
        }
        try {
            Product to = products.findById(toId).orElse(null);
            Product from = products.findById(fromId).orElse(null);
            if (to == null || from == null) return ResponseEntity.notFound().build();

            List<SitePrice> combined = new ArrayList<>(to.getPrices() == null ? List.of() : to.getPrices());
            if (from.getPrices() != null) {
                for (SitePrice sp : from.getPrices()) {
                    boolean dup = combined.stream().anyMatch(
                            x -> java.util.Objects.equals(x.getProductUrl(), sp.getProductUrl()));
                    if (!dup) combined.add(sp);
                }
            }
            to.setPrices(combined);

            // Re-aggregate min / max / count
            double low = Double.MAX_VALUE, high = 0;
            for (SitePrice sp : combined) {
                if (sp.getPrice() == null) continue;
                if (sp.getPrice() < low) low = sp.getPrice();
                if (sp.getPrice() > high) high = sp.getPrice();
            }
            to.setLowestPrice(low == Double.MAX_VALUE ? null : low);
            to.setHighestPrice(high == 0 ? null : high);

            // Inherit descriptive fields if missing
            if (to.getImageUrl() == null && from.getImageUrl() != null) to.setImageUrl(from.getImageUrl());
            if (to.getDescription() == null && from.getDescription() != null) to.setDescription(from.getDescription());
            if (to.getCategory() == null && from.getCategory() != null) to.setCategory(from.getCategory());

            to.setUpdatedAt(LocalDateTime.now());
            products.save(to);
            products.deleteById(fromId);
            log.info("AdminCatalog: merged {} → {}", fromId, toId);

            return ResponseEntity.ok(Map.of("ok", true, "id", toId, "sellerCount", combined.size()));
        } catch (DataAccessException e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }
}
