package com.damKemon.dam.kemon.controller;

import com.damKemon.dam.kemon.model.Product;
import com.damKemon.dam.kemon.repository.ProductRepository;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Operator surface for paid placements. Admins flag a Product as
 * sponsored (optionally for a category and a duration); the search
 * service injects the top sponsored product into slot 0 of any matching
 * query.
 *
 * <p>Two endpoints:
 * <ul>
 *   <li>{@code POST /api/admin/sponsorships} — turn ON sponsorship for a
 *       product. Idempotent; updates the expiry/tier in place.</li>
 *   <li>{@code DELETE /api/admin/sponsorships/{productId}} — turn OFF.</li>
 *   <li>{@code GET /api/admin/sponsorships} — list active ones for the
 *       admin dashboard.</li>
 * </ul>
 *
 * Billing reconciliation lives elsewhere — this endpoint only flips the
 * flag. Impressions / clicks are recorded by the search and affiliate
 * paths respectively so finance can join them by productId.
 */
@RestController
@RequestMapping("/api/admin/sponsorships")
public class AdminSponsorshipController {

    private final ProductRepository products;

    public AdminSponsorshipController(ProductRepository products) {
        this.products = products;
    }

    @GetMapping
    public ResponseEntity<?> list(@RequestParam(value = "limit", defaultValue = "50") int limit) {
        try {
            int capped = Math.max(1, Math.min(limit, 500));
            List<Product> rows = products.findAllSponsored(PageRequest.of(0, capped));
            return ResponseEntity.ok(rows);
        } catch (DataAccessException e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Body shape:
     * <pre>{ "productId": "...", "days": 7, "tier": 1 }</pre>
     * {@code days} defaults to 30, {@code tier} defaults to 2.
     */
    @PostMapping
    public ResponseEntity<?> create(@RequestBody Map<String, Object> body) {
        if (body == null) return ResponseEntity.badRequest().body(Map.of("error", "body required"));
        String productId = (String) body.get("productId");
        if (productId == null || productId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "productId required"));
        }

        int days = body.get("days") instanceof Number n ? n.intValue() : 30;
        int tier = body.get("tier") instanceof Number n ? n.intValue() : 2;

        Product p = products.findById(productId).orElse(null);
        if (p == null) return ResponseEntity.notFound().build();

        p.setSponsored(true);
        p.setSponsoredUntil(LocalDateTime.now().plusDays(Math.max(1, Math.min(days, 365))));
        p.setSponsorTier(Math.max(1, Math.min(tier, 3)));
        p.setUpdatedAt(LocalDateTime.now());
        try {
            products.save(p);
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("productId", p.getId());
            resp.put("sponsoredUntil", p.getSponsoredUntil());
            resp.put("tier", p.getSponsorTier());
            return ResponseEntity.ok(resp);
        } catch (DataAccessException e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "could not save"));
        }
    }

    @DeleteMapping("/{productId}")
    public ResponseEntity<?> revoke(@PathVariable String productId) {
        Product p = products.findById(productId).orElse(null);
        if (p == null) return ResponseEntity.notFound().build();
        p.setSponsored(false);
        p.setSponsoredUntil(null);
        p.setSponsorTier(null);
        p.setUpdatedAt(LocalDateTime.now());
        try {
            products.save(p);
            return ResponseEntity.noContent().build();
        } catch (DataAccessException e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "could not save"));
        }
    }
}
