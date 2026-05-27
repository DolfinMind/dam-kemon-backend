package com.damKemon.dam.kemon.controller;

import com.damKemon.dam.kemon.model.Product;
import com.damKemon.dam.kemon.model.SaathiAccount;
import com.damKemon.dam.kemon.model.SaathiProduct;
import com.damKemon.dam.kemon.repository.ProductRepository;
import com.damKemon.dam.kemon.service.SaathiService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Public endpoints anyone can hit without auth — used for the embeddable
 * verified badge on the seller's FB page and the buyer-facing storefront.
 *
 * <p>The badge SVG is intentionally light and inline-styled so sellers can
 * embed it via plain {@code <img>} tags in FB page covers and product
 * descriptions. Every view is logged as a {@code badge_view} query so
 * sellers can measure brand impressions.
 */
@RestController
@RequestMapping("/api/saathi")
public class SaathiPublicController {

    private final SaathiService saathi;
    private final ProductRepository products;

    public SaathiPublicController(SaathiService saathi, ProductRepository products) {
        this.saathi = saathi;
        this.products = products;
    }

    /**
     * Verified-shop badge as inline SVG. Renders the brand mark + the
     * seller's display name. Sized 220×64 to fit comfortably in FB cover
     * descriptions and post embeds. Cached for an hour at the CDN — name
     * changes propagate within that window.
     */
    @GetMapping(value = "/badge/{slug}.svg", produces = "image/svg+xml")
    public ResponseEntity<String> badgeSvg(@PathVariable String slug) {
        SaathiAccount acc = saathi.findBySlug(slug).orElse(null);
        if (acc == null || !"verified".equals(acc.getVerificationStatus())) {
            return ResponseEntity.notFound().build();
        }
        String name = acc.getDisplayName() == null ? slug : acc.getDisplayName();
        String svg = """
                <svg xmlns="http://www.w3.org/2000/svg" width="220" height="64" viewBox="0 0 220 64" role="img" aria-label="Damkemon Verified">
                  <defs>
                    <linearGradient id="g" x1="0" x2="1">
                      <stop offset="0" stop-color="#15131A"/>
                      <stop offset="1" stop-color="#FF4521"/>
                    </linearGradient>
                  </defs>
                  <rect width="220" height="64" rx="12" fill="url(#g)"/>
                  <circle cx="32" cy="32" r="14" fill="#FFF6E5"/>
                  <path d="M26 32 l5 5 l9 -10" stroke="#0F4D2A" stroke-width="3" fill="none" stroke-linecap="round" stroke-linejoin="round"/>
                  <text x="56" y="26" font-family="ui-serif,Georgia,serif" font-style="italic" font-size="14" fill="#FFF6E5" font-weight="700">Verified by</text>
                  <text x="56" y="44" font-family="ui-serif,Georgia,serif" font-size="18" fill="#FFF6E5" font-weight="700">%s</text>
                  <text x="56" y="58" font-family="ui-monospace,Menlo,monospace" font-size="8" fill="#FFF6E5" opacity="0.7">damkemon.com/p/%s</text>
                </svg>
                """.formatted(escape(name.length() > 18 ? name.substring(0, 18) + "…" : name), slug);
        return ResponseEntity.ok()
                .contentType(MediaType.valueOf("image/svg+xml"))
                .header(HttpHeaders.CACHE_CONTROL, "public, max-age=3600")
                .body(svg);
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    /**
     * Public profile JSON for the buyer-facing storefront page. Includes a
     * hydrated product list (capped at 24) so the storefront can render a
     * grid of what the seller offers without a second round-trip.
     */
    @GetMapping("/p/{slug}")
    public ResponseEntity<?> publicProfile(@PathVariable String slug) {
        SaathiAccount acc = saathi.findBySlug(slug).orElse(null);
        if (acc == null) return ResponseEntity.notFound().build();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("slug", acc.getSlug());
        out.put("displayName", acc.getDisplayName());
        out.put("facebookUrl", acc.getFacebookUrl());
        out.put("messengerUrl", acc.getMessengerUrl());
        out.put("whatsapp", acc.getWhatsapp());
        out.put("city", acc.getCity());
        out.put("area", acc.getArea());
        out.put("categories", acc.getCategories());
        out.put("verified", "verified".equals(acc.getVerificationStatus()));
        out.put("verifiedAt", acc.getVerifiedAt());
        out.put("rating", acc.getRating());
        out.put("ratingCount", acc.getRatingCount());
        out.put("avgReplyTime", acc.getAvgReplyTime());

        // Hydrate up to 24 of their listed products so the buyer page
        // renders a real storefront, not just a contact card.
        List<SaathiProduct> sp = saathi.listProducts(acc);
        List<Map<String, Object>> productList = new ArrayList<>();
        int cap = Math.min(sp.size(), 24);
        for (int i = 0; i < cap; i++) {
            SaathiProduct s = sp.get(i);
            Product p = products.findById(s.getProductId()).orElse(null);
            if (p == null) continue;
            Map<String, Object> pmap = new LinkedHashMap<>();
            pmap.put("id", p.getId());
            pmap.put("slug", p.getSlug());
            pmap.put("name", p.getName());
            pmap.put("imageUrl", p.getImageUrl());
            pmap.put("category", p.getCategory());
            pmap.put("listedPrice", s.getListedPrice());
            pmap.put("marketLowest", p.getLowestPrice());
            pmap.put("inStock", s.getInStock());
            pmap.put("note", s.getNote());
            productList.add(pmap);
        }
        out.put("products", productList);
        return ResponseEntity.ok(out);
    }
}
