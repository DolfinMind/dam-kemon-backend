package com.damKemon.dam.kemon.controller;

import com.damKemon.dam.kemon.model.PendingShop;
import com.damKemon.dam.kemon.model.Shop;
import com.damKemon.dam.kemon.repository.PendingShopRepository;
import com.damKemon.dam.kemon.repository.ShopRepository;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Public "Submit your shop" endpoint. Shop owners paste a base URL +
 * sitemap; we capture the submission for admin review. No automated
 * promotion — every accepted shop is operator-approved.
 */
@RestController
@RequestMapping("/api/shops")
public class SubmitShopController {

    private static final Logger log = LoggerFactory.getLogger(SubmitShopController.class);

    private final PendingShopRepository pendingRepo;
    private final ShopRepository shopRepo;
    private final MongoTemplate mongoTemplate;

    public SubmitShopController(PendingShopRepository pendingRepo, ShopRepository shopRepo,
                                MongoTemplate mongoTemplate) {
        this.pendingRepo = pendingRepo;
        this.shopRepo = shopRepo;
        this.mongoTemplate = mongoTemplate;
    }

    /**
     * Public shop directory — the active indexed shops with their catalog size
     * (how many products list each shop). Powers the shop-vs-shop comparison
     * picker; the trust/delivery/returns signals for the selected shops are
     * fetched separately via {@code GET /api/trust/shops}.
     */
    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> directory() {
        try {
            Map<String, Integer> counts = productCountsBySlug();
            List<Map<String, Object>> out = shopRepo.findByStatus("active").stream()
                    .map(s -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("slug", s.getSlug());
                        m.put("name", s.getName());
                        m.put("platform", s.getPlatform());
                        m.put("productCount", counts.getOrDefault(s.getSlug(), 0));
                        return m;
                    })
                    .sorted((a, b) -> Integer.compare((Integer) b.get("productCount"), (Integer) a.get("productCount")))
                    .collect(Collectors.toList());
            return ResponseEntity.ok(out);
        } catch (DataAccessException e) {
            return ResponseEntity.ok(List.of());
        }
    }

    /** Catalog size per shop slug: number of products that list the shop. */
    private Map<String, Integer> productCountsBySlug() {
        Map<String, Integer> counts = new HashMap<>();
        try {
            Aggregation agg = Aggregation.newAggregation(
                    Aggregation.unwind("prices"),
                    Aggregation.group("prices.siteSlug").count().as("n"));
            AggregationResults<Document> res = mongoTemplate.aggregate(agg, "products", Document.class);
            for (Document d : res) {
                String slug = d.getString("_id");
                Object n = d.get("n");
                if (slug != null && n instanceof Number) counts.put(slug, ((Number) n).intValue());
            }
        } catch (Exception ignored) { /* best-effort; counts just show as 0 */ }
        return counts;
    }

    @PostMapping("/submit")
    public ResponseEntity<Map<String, Object>> submit(@RequestBody Map<String, Object> body) {
        String name = trim((String) body.get("name"));
        String baseUrl = trim((String) body.get("baseUrl"));
        String sitemapUrl = trim((String) body.get("sitemapUrl"));
        String platform = trim((String) body.get("platform"));
        String notes = trim((String) body.get("notes"));
        String contactEmail = trim((String) body.get("contactEmail"));

        if (name == null || name.length() < 2 || name.length() > 80) {
            return ResponseEntity.badRequest().body(Map.of("error", "name is required (2–80 chars)"));
        }
        if (baseUrl == null || !isHttpUrl(baseUrl)) {
            return ResponseEntity.badRequest().body(Map.of("error", "baseUrl must be an absolute http(s) URL"));
        }
        if (sitemapUrl != null && !sitemapUrl.isBlank() && !isHttpUrl(sitemapUrl)) {
            return ResponseEntity.badRequest().body(Map.of("error", "sitemapUrl must be an absolute http(s) URL"));
        }
        String host;
        try { host = URI.create(baseUrl).getHost(); }
        catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("error", "baseUrl is malformed")); }
        if (host == null || host.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "baseUrl has no host"));
        }

        try {
            String normalised = stripTrailingSlash(baseUrl);
            // Already approved?
            if (shopRepo.findAll().stream()
                    .anyMatch(s -> normalised.equalsIgnoreCase(stripTrailingSlash(s.getBaseUrl())))) {
                return ResponseEntity.status(409).body(Map.of("error", "shop already in our catalog"));
            }
            // Already pending?
            if (pendingRepo.findByBaseUrl(normalised).isPresent()) {
                return ResponseEntity.status(409).body(Map.of("error", "shop already submitted, awaiting review"));
            }

            PendingShop p = PendingShop.builder()
                    .name(name)
                    .baseUrl(normalised)
                    .sitemapUrl(sitemapUrl == null || sitemapUrl.isBlank() ? null : sitemapUrl)
                    .platform(platform)
                    .notes(notes)
                    .contactEmail(contactEmail)
                    .status("pending")
                    .submittedAt(LocalDateTime.now())
                    .build();
            pendingRepo.save(p);
            log.info("Submit-shop: queued '{}' ({}) for review", name, normalised);
            java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
            out.put("submitted", true);
            if (p.getId() != null) out.put("id", p.getId());
            out.put("message", "Thanks! We'll review and email you when it's live.");
            return ResponseEntity.accepted().body(out);
        } catch (DataAccessException e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "could not save submission"));
        }
    }

    private static String trim(String s) { return s == null ? null : s.trim(); }

    private static boolean isHttpUrl(String s) {
        try {
            URI u = URI.create(s);
            return u.getScheme() != null && (u.getScheme().equals("http") || u.getScheme().equals("https"))
                    && u.getHost() != null && !u.getHost().isBlank();
        } catch (Exception e) {
            return false;
        }
    }

    private static String stripTrailingSlash(String s) {
        if (s == null) return null;
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
