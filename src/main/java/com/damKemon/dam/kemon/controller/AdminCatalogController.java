package com.damKemon.dam.kemon.controller;

import com.damKemon.dam.kemon.indexer.BulkIndexer;
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
import org.bson.Document;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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

    /**
     * Seller-depth report — the core business metric. Server-side aggregation over
     * {@code prices[]} length, so it's heap-safe at any catalog size. Returns the
     * average sellers/product, the single-vs-multi split, and the deepest products.
     * This is the number to watch: more sellers/product = real price comparison.
     */
    @GetMapping("/seller-depth")
    public ResponseEntity<?> sellerDepth() {
        try {
            List<Document> pipeline = List.of(
                new Document("$project", new Document("sellers",
                        new Document("$size", new Document("$ifNull", List.of("$prices", List.of()))))),
                new Document("$group", new Document("_id", null)
                        .append("products", new Document("$sum", 1))
                        .append("totalOffers", new Document("$sum", "$sellers"))
                        .append("avgSellers", new Document("$avg", "$sellers"))
                        .append("maxSellers", new Document("$max", "$sellers"))
                        .append("single", countWhen(new Document("$lte", List.of("$sellers", 1))))
                        .append("multi",  countWhen(new Document("$gte", List.of("$sellers", 2))))
                        .append("atLeast3", countWhen(new Document("$gte", List.of("$sellers", 3))))
                        .append("atLeast5", countWhen(new Document("$gte", List.of("$sellers", 5))))));
            Document r = mongo.getCollection("products").aggregate(pipeline).first();
            Map<String, Object> out = new LinkedHashMap<>();
            if (r == null) { out.put("products", 0); return ResponseEntity.ok(out); }
            long prods = num(r.get("products"));
            long multi = num(r.get("multi"));
            double avg = r.get("avgSellers") == null ? 0 : ((Number) r.get("avgSellers")).doubleValue();
            out.put("products", prods);
            out.put("totalOffers", num(r.get("totalOffers")));
            out.put("avgSellersPerProduct", Math.round(avg * 100.0) / 100.0);
            out.put("maxSellers", num(r.get("maxSellers")));
            out.put("singleSeller", num(r.get("single")));
            out.put("multiSeller", multi);
            out.put("multiSellerPct", prods == 0 ? 0 : Math.round(multi * 1000.0 / prods) / 10.0);
            out.put("atLeast3Sellers", num(r.get("atLeast3")));
            out.put("atLeast5Sellers", num(r.get("atLeast5")));

            // The deepest products — what good comparison looks like today.
            List<Document> topPipe = List.of(
                new Document("$project", new Document("name", 1)
                        .append("sellers", new Document("$size", new Document("$ifNull", List.of("$prices", List.of()))))),
                new Document("$sort", new Document("sellers", -1)),
                new Document("$limit", 10));
            List<Map<String, Object>> top = new ArrayList<>();
            for (Document d : mongo.getCollection("products").aggregate(topPipe)) {
                Map<String, Object> t = new LinkedHashMap<>();
                t.put("name", d.getString("name"));
                t.put("sellers", num(d.get("sellers")));
                top.add(t);
            }
            out.put("deepestProducts", top);
            return ResponseEntity.ok(out);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    private static Document countWhen(Document predicate) {
        return new Document("$sum", new Document("$cond", List.of(predicate, 1, 0)));
    }

    private static long num(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }

    /** Edit core fields of a product. Operator override of indexer detection. */
    public record OfferReq(String siteSlug, String siteName, Double price, Double originalPrice,
                           String productUrl, String imageUrl, String sellerName) {}

    /**
     * Append (or refresh) one seller's offer on a SPECIFIC product — the precise,
     * lane-aware way to add a hand/web-verified price without the matchKey ingest
     * possibly routing it to the wrong variant doc (official vs grey-market). A
     * re-add from the same first-party shop replaces its prior offer. Aggregates
     * are recomputed through the same trusted-price filter the indexer uses.
     */
    @PostMapping("/{id}/offer")
    public ResponseEntity<?> addOffer(@PathVariable String id, @RequestBody OfferReq req) {
        if (req == null || req.price() == null || req.price() <= 0
                || req.siteSlug() == null || req.siteSlug().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "siteSlug and a positive price are required"));
        }
        try {
            Product p = products.findById(id).orElse(null);
            if (p == null) return ResponseEntity.notFound().build();
            if (p.getPrices() == null) p.setPrices(new ArrayList<>());
            // one offer per first-party shop: drop the same shop's prior offer, then add.
            p.getPrices().removeIf(o -> java.util.Objects.equals(o.getSiteSlug(), req.siteSlug())
                    && (o.getSellerId() == null || o.getSellerId().isBlank()));
            p.getPrices().add(SitePrice.builder()
                    .siteName(req.siteName() != null && !req.siteName().isBlank() ? req.siteName() : req.siteSlug())
                    .siteSlug(req.siteSlug())
                    .productUrl(req.productUrl())
                    .price(req.price())
                    .originalPrice(req.originalPrice())
                    .currency("BDT")
                    .inStock(true)
                    .sellerName(req.sellerName())
                    .lastUpdated(LocalDateTime.now())
                    .build());
            if ((p.getImageUrl() == null || p.getImageUrl().isBlank()) && req.imageUrl() != null) {
                p.setImageUrl(req.imageUrl());
            }
            BulkIndexer.recomputeAggregates(p);
            p.setUpdatedAt(LocalDateTime.now());
            products.save(p);
            return ResponseEntity.ok(Map.of("id", id, "name", p.getName(),
                    "sellers", p.getPrices().size(),
                    "lowestPrice", String.valueOf(p.getLowestPrice()),
                    "highestPrice", String.valueOf(p.getHighestPrice())));
        } catch (DataAccessException e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

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
