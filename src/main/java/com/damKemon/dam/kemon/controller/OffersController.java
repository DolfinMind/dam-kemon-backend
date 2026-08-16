package com.damKemon.dam.kemon.controller;

import com.damKemon.dam.kemon.model.PendingOffer;
import com.damKemon.dam.kemon.model.Product;
import com.damKemon.dam.kemon.model.SitePrice;
import com.damKemon.dam.kemon.repository.PendingOfferRepository;
import com.damKemon.dam.kemon.repository.ProductRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Community offers — shoppers/sellers submit "I sell this for ৳X here" against an
 * existing product. Submissions are held in {@code pending_offers} for operator
 * review; on approval they become a {@link SitePrice} comparison row, raising
 * sellers-per-product without a crawl. This is the push side of supply: depth
 * comes from demand, not just scraping.
 *
 * <p>Anonymous input is a trust boundary — nothing goes live unmoderated, the
 * price is range-checked, and the URL must be absolute http(s).
 */
@RestController
public class OffersController {

    private static final Logger log = LoggerFactory.getLogger(OffersController.class);
    private static final double MAX_PRICE = 100_000_000;   // ৳10cr sanity cap

    private final PendingOfferRepository offers;
    private final ProductRepository products;

    public OffersController(PendingOfferRepository offers, ProductRepository products) {
        this.offers = offers;
        this.products = products;
    }

    // ── Public: submit an offer ──────────────────────────────────────────────

    @PostMapping("/api/products/{productId}/offers")
    public ResponseEntity<Map<String, Object>> submit(@PathVariable String productId,
                                                      @RequestBody Map<String, Object> body,
                                                      @RequestHeader(value = "X-Anon-Id", required = false) String anonId,
                                                      HttpServletRequest req) {
        Product product = products.findById(productId).orElse(null);
        if (product == null) return ResponseEntity.notFound().build();

        String shopName = trim((String) body.get("shopName"));
        String url = trim((String) body.get("url"));
        String contactEmail = trim((String) body.get("contactEmail"));
        Double price = toPrice(body.get("price"));

        if (shopName == null || shopName.length() < 2 || shopName.length() > 80) {
            return ResponseEntity.badRequest().body(Map.of("error", "Shop name is required (2–80 chars)."));
        }
        if (url == null || !isHttpUrl(url)) {
            return ResponseEntity.badRequest().body(Map.of("error", "A valid product link (http/https) is required."));
        }
        if (price == null || price <= 0 || price > MAX_PRICE) {
            return ResponseEntity.badRequest().body(Map.of("error", "Enter a valid price in ৳."));
        }

        try {
            if (offers.existsByProductIdAndUrlAndStatus(productId, url, "pending")) {
                return ResponseEntity.status(409).body(Map.of("error", "That offer is already awaiting review."));
            }
            offers.save(PendingOffer.builder()
                    .productId(productId)
                    .productName(product.getName())
                    .shopName(shopName)
                    .url(url)
                    .price(price)
                    .contactEmail(contactEmail)
                    .submittedByAnon(anonId)
                    .status("pending")
                    .submittedAt(LocalDateTime.now())
                    .build());
            log.info("Offer submitted for product {} from '{}' at ৳{}", productId, shopName, price);
            return ResponseEntity.accepted().body(Map.of("submitted", true,
                    "message", "Thanks! We'll verify and add it to the comparison."));
        } catch (DataAccessException e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "Could not save your submission."));
        }
    }

    // ── Admin: moderate ──────────────────────────────────────────────────────

    @GetMapping("/api/admin/offers")
    public ResponseEntity<List<PendingOffer>> listPending() {
        try { return ResponseEntity.ok(offers.findByStatusOrderBySubmittedAtDesc("pending")); }
        catch (DataAccessException e) { return ResponseEntity.ok(List.of()); }
    }

    @PostMapping("/api/admin/offers/{id}/approve")
    public ResponseEntity<Map<String, Object>> approve(@PathVariable String id) {
        try {
            PendingOffer o = offers.findById(id).orElse(null);
            if (o == null) return ResponseEntity.notFound().build();
            Product p = products.findById(o.getProductId()).orElse(null);
            if (p == null) return ResponseEntity.status(409).body(Map.of("error", "Product no longer exists."));

            if (p.getPrices() == null) p.setPrices(new java.util.ArrayList<>());
            p.getPrices().add(SitePrice.builder()
                    .siteName(o.getShopName())
                    .siteSlug(slugify(o.getShopName()))
                    .productUrl(o.getUrl())
                    .price(o.getPrice())
                    .currency("BDT")
                    .inStock(true)
                    .sellerName(o.getShopName())
                    .lastUpdated(LocalDateTime.now())
                    .build());
            recomputePriceBounds(p);
            p.setUpdatedAt(LocalDateTime.now());
            products.save(p);

            o.setStatus("approved");
            o.setReviewedAt(LocalDateTime.now());
            offers.save(o);
            return ResponseEntity.ok(Map.of("approved", true, "sellers", p.getPrices().size()));
        } catch (DataAccessException e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/api/admin/offers/{id}/reject")
    public ResponseEntity<Map<String, Object>> reject(@PathVariable String id,
                                                      @RequestBody(required = false) Map<String, String> body) {
        try {
            PendingOffer o = offers.findById(id).orElse(null);
            if (o == null) return ResponseEntity.notFound().build();
            o.setStatus("rejected");
            o.setReviewedAt(LocalDateTime.now());
            if (body != null) o.setReviewNote(body.get("note"));
            offers.save(o);
            return ResponseEntity.ok(Map.of("rejected", true));
        } catch (DataAccessException e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static void recomputePriceBounds(Product p) {
        Double lo = null, hi = null;
        for (SitePrice sp : p.getPrices()) {
            if (sp.getPrice() == null) continue;
            if (lo == null || sp.getPrice() < lo) lo = sp.getPrice();
            if (hi == null || sp.getPrice() > hi) hi = sp.getPrice();
        }
        p.setLowestPrice(lo);
        p.setHighestPrice(hi);
    }

    private static Double toPrice(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        if (o instanceof String s) { try { return Double.parseDouble(s.trim()); } catch (Exception e) { return null; } }
        return null;
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

    private static String slugify(String name) {
        String s = name.toLowerCase().replaceAll("[^a-z0-9\\s-]", "")
                .replaceAll("\\s+", "-").replaceAll("-+", "-").replaceAll("^-|-$", "");
        return s.isBlank() ? "community" : s;
    }
}
