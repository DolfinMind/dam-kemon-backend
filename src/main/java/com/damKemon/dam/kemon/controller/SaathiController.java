package com.damKemon.dam.kemon.controller;

import com.damKemon.dam.kemon.model.Product;
import com.damKemon.dam.kemon.model.SaathiAccount;
import com.damKemon.dam.kemon.model.SaathiProduct;
import com.damKemon.dam.kemon.model.SaathiQuery;
import com.damKemon.dam.kemon.repository.ProductRepository;
import com.damKemon.dam.kemon.service.SaathiService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Public + authenticated surface for Damkemon Saathi merchants.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code POST /api/saathi/signup} — register the signed-in user as a Saathi.</li>
 *   <li>{@code GET  /api/saathi/me} — full profile of the calling Saathi.</li>
 *   <li>{@code PATCH /api/saathi/me} — update profile fields.</li>
 *   <li>{@code POST /api/saathi/verify} — submit NID / trade license for review.</li>
 *   <li>{@code GET/POST/DELETE /api/saathi/products...} — manage listed products.</li>
 *   <li>{@code GET /api/saathi/live-assist?q=...} — the FB-Live sidebar query.</li>
 *   <li>{@code GET /api/saathi/queries} — recent query log for the dashboard.</li>
 * </ul>
 * The Messenger webhook + public badge/profile live in separate controllers
 * so this file stays focused on the authenticated dashboard surface.
 */
@RestController
@RequestMapping("/api/saathi")
public class SaathiController {

    private final SaathiService saathi;
    private final ProductRepository products;

    public SaathiController(SaathiService saathi, ProductRepository products) {
        this.saathi = saathi;
        this.products = products;
    }

    @PostMapping("/signup")
    public ResponseEntity<?> signup(@RequestBody(required = false) Map<String, Object> body,
                                    HttpServletRequest req) {
        String userId = userId(req);
        if (userId == null) return unauth();
        try {
            SaathiAccount acc = saathi.signup(userId, body == null ? Map.of() : body);
            return ResponseEntity.ok(acc);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (DataAccessException e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "could not save"));
        }
    }

    @GetMapping("/me")
    public ResponseEntity<?> me(HttpServletRequest req) {
        String userId = userId(req);
        if (userId == null) return unauth();
        return saathi.findByUser(userId)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(404).body(Map.of("error", "no_saathi_account")));
    }

    @PatchMapping("/me")
    public ResponseEntity<?> updateMe(@RequestBody Map<String, Object> patch, HttpServletRequest req) {
        String userId = userId(req);
        if (userId == null) return unauth();
        SaathiAccount acc = saathi.findByUser(userId).orElse(null);
        if (acc == null) return ResponseEntity.status(404).body(Map.of("error", "no_saathi_account"));
        return ResponseEntity.ok(saathi.update(acc, patch == null ? Map.of() : patch));
    }

    @PostMapping("/verify")
    public ResponseEntity<?> submitVerification(@RequestBody Map<String, String> body,
                                                HttpServletRequest req) {
        String userId = userId(req);
        if (userId == null) return unauth();
        SaathiAccount acc = saathi.findByUser(userId).orElse(null);
        if (acc == null) return ResponseEntity.status(404).body(Map.of("error", "no_saathi_account"));
        String nid = body == null ? null : body.get("nid");
        String tradeLicense = body == null ? null : body.get("tradeLicense");
        if ((nid == null || nid.isBlank()) && (tradeLicense == null || tradeLicense.isBlank())) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "provide NID or trade license"));
        }
        return ResponseEntity.ok(saathi.submitVerification(acc, nid, tradeLicense));
    }

    @GetMapping("/products")
    public ResponseEntity<?> listProducts(HttpServletRequest req) {
        String userId = userId(req);
        if (userId == null) return unauth();
        SaathiAccount acc = saathi.findByUser(userId).orElse(null);
        if (acc == null) return ResponseEntity.status(404).body(Map.of("error", "no_saathi_account"));

        List<SaathiProduct> rows = saathi.listProducts(acc);
        // Hydrate with the catalog product so the dashboard doesn't need a 2nd hop
        List<Map<String, Object>> out = new ArrayList<>();
        for (SaathiProduct sp : rows) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", sp.getId());
            row.put("productId", sp.getProductId());
            row.put("listedPrice", sp.getListedPrice());
            row.put("note", sp.getNote());
            row.put("inStock", sp.getInStock());
            row.put("updatedAt", sp.getUpdatedAt());
            Product p = products.findById(sp.getProductId()).orElse(null);
            if (p != null) {
                row.put("product", Map.of(
                        "id", p.getId(),
                        "slug", p.getSlug() == null ? "" : p.getSlug(),
                        "name", p.getName() == null ? "" : p.getName(),
                        "imageUrl", p.getImageUrl() == null ? "" : p.getImageUrl(),
                        "category", p.getCategory() == null ? "" : p.getCategory(),
                        "lowestPrice", p.getLowestPrice() == null ? 0 : p.getLowestPrice(),
                        "sellerCount", p.getPrices() == null ? 0 : p.getPrices().size()
                ));
            } else {
                row.put("missing", true);
            }
            out.add(row);
        }
        return ResponseEntity.ok(out);
    }

    /**
     * Body: {@code {"productId":"...", "listedPrice": 52500, "note":"..." }}.
     * Idempotent: re-posting the same productId updates the price.
     */
    @PostMapping("/products")
    public ResponseEntity<?> attach(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        String userId = userId(req);
        if (userId == null) return unauth();
        SaathiAccount acc = saathi.findByUser(userId).orElse(null);
        if (acc == null) return ResponseEntity.status(404).body(Map.of("error", "no_saathi_account"));

        String productId = body == null ? null : Objects.toString(body.get("productId"), null);
        if (productId == null || productId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "productId required"));
        }
        Double listedPrice = body.get("listedPrice") instanceof Number n ? n.doubleValue() : null;
        String note = Objects.toString(body.get("note"), null);
        return ResponseEntity.ok(saathi.attachProduct(acc, productId, listedPrice, note));
    }

    @DeleteMapping("/products/{productId}")
    public ResponseEntity<?> detach(@PathVariable String productId, HttpServletRequest req) {
        String userId = userId(req);
        if (userId == null) return unauth();
        SaathiAccount acc = saathi.findByUser(userId).orElse(null);
        if (acc == null) return ResponseEntity.status(404).body(Map.of("error", "no_saathi_account"));
        saathi.detachProduct(acc, productId);
        return ResponseEntity.noContent().build();
    }

    /**
     * The live-assist endpoint. Called by the FB-Live sidebar and the
     * Saathi dashboard. Returns the best catalog match + the seller's
     * own listing (if any) so the UI can render a side-by-side panel.
     *
     * <p>Gated by entitlement (trial OR paid) and a per-tier daily quota.
     * Suspended accounts always 403 — keeps bad actors from continuing to
     * use the toolkit after we revoke their verification.
     */
    @GetMapping("/live-assist")
    public ResponseEntity<?> liveAssist(@RequestParam("q") String q, HttpServletRequest req) {
        String userId = userId(req);
        if (userId == null) return unauth();
        SaathiAccount acc = saathi.findByUser(userId).orElse(null);
        if (acc == null) return ResponseEntity.status(404).body(Map.of("error", "no_saathi_account"));
        if ("suspended".equals(acc.getVerificationStatus())) {
            return ResponseEntity.status(403).body(Map.of("error", "account_suspended", "note", acc.getVerificationNote()));
        }
        if (!saathi.isEntitled(acc)) {
            return ResponseEntity.status(402).body(Map.of(
                    "error", "trial_expired",
                    "message", "Your free trial has ended. Upgrade to Saathi Lite or Pro to keep using live-assist.",
                    "upgradeUrl", "/saathi#pricing"));
        }
        Map<String, Object> quotaError = saathi.checkQuota(acc);
        if (quotaError != null) return ResponseEntity.status(429).body(quotaError);

        if (q == null || q.trim().length() < 2) {
            return ResponseEntity.badRequest().body(Map.of("error", "q must be ≥ 2 chars"));
        }
        return ResponseEntity.ok(saathi.liveAssist(acc, q.trim(), "live_assist"));
    }

    /** Aggregate metrics for the dashboard header. */
    @GetMapping("/stats")
    public ResponseEntity<?> stats(HttpServletRequest req) {
        String userId = userId(req);
        if (userId == null) return unauth();
        SaathiAccount acc = saathi.findByUser(userId).orElse(null);
        if (acc == null) return ResponseEntity.status(404).body(Map.of("error", "no_saathi_account"));
        return ResponseEntity.ok(saathi.dashboardStats(acc));
    }

    @GetMapping("/queries")
    public ResponseEntity<?> recentQueries(@RequestParam(value = "limit", defaultValue = "30") int limit,
                                           HttpServletRequest req) {
        String userId = userId(req);
        if (userId == null) return unauth();
        SaathiAccount acc = saathi.findByUser(userId).orElse(null);
        if (acc == null) return ResponseEntity.status(404).body(Map.of("error", "no_saathi_account"));
        List<SaathiQuery> rows = saathi.recentQueries(acc, limit);
        return ResponseEntity.ok(rows);
    }

    private static String userId(HttpServletRequest req) {
        Object id = req.getAttribute("authUserId");
        return id instanceof String ? (String) id : null;
    }

    private static ResponseEntity<Map<String, Object>> unauth() {
        return ResponseEntity.status(401).body(Map.of("error", "sign in to use Saathi"));
    }
}
