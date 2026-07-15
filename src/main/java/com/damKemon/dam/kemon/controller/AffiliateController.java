package com.damKemon.dam.kemon.controller;

import com.damKemon.dam.kemon.model.AffiliateClick;
import com.damKemon.dam.kemon.model.Product;
import com.damKemon.dam.kemon.model.SitePrice;
import com.damKemon.dam.kemon.repository.AffiliateClickRepository;
import com.damKemon.dam.kemon.repository.ProductRepository;
import com.damKemon.dam.kemon.service.AffiliateService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.Cookie;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

/**
 * Tracked outbound redirect: {@code GET /api/r/{productId}?site=daraz} →
 * 302 to the partner-decorated URL, logging an {@link AffiliateClick}
 * along the way. Frontend uses this in place of bare {@code productUrl}
 * anchors so every outbound click is attributable.
 *
 * <p>The redirect is cheap and synchronous. We persist the click on the
 * happy path but never let a write failure block the redirect — losing
 * one click row is far better than failing the user's outbound jump.
 */
@RestController
@RequestMapping("/api/r")
public class AffiliateController {

    private static final Logger log = LoggerFactory.getLogger(AffiliateController.class);

    private final ProductRepository products;
    private final AffiliateClickRepository clicks;
    private final AffiliateService affiliate;

    public AffiliateController(ProductRepository products,
                               AffiliateClickRepository clicks,
                               AffiliateService affiliate) {
        this.products = products;
        this.clicks = clicks;
        this.affiliate = affiliate;
    }

    @GetMapping("/{productId}")
    public ResponseEntity<?> redirect(@PathVariable String productId,
                                      @RequestParam(value = "site", required = false) String site,
                                      @RequestParam(value = "u", required = false) String offerUrl,
                                      @RequestParam(value = "q", required = false) String fromQuery,
                                      HttpServletRequest req) {
        Product p = products.findById(productId).orElse(null);
        if (p == null || p.getPrices() == null || p.getPrices().isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        SitePrice chosen = pickSite(p, site, offerUrl);
        if (chosen == null || chosen.getProductUrl() == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body("No URL available for that site");
        }

        String clickId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String outbound = affiliate.decorate(chosen.getProductUrl(), chosen.getSiteSlug(), clickId);

        try {
            clicks.save(AffiliateClick.builder()
                    .productId(p.getId())
                    .siteSlug(chosen.getSiteSlug())
                    .category(p.getCategory())
                    .productName(p.getName())
                    .userId(asString(req.getAttribute("authUserId")))
                    .anonId(anonIdFrom(req.getCookies(), req.getHeader("X-Anon-Id")))
                    .clickId(clickId)
                    .fromQuery(fromQuery)
                    .referer(req.getHeader("Referer"))
                    .userAgent(req.getHeader("User-Agent"))
                    .ip(clientIp(req))
                    .outboundUrl(outbound)
                    .ts(Instant.now())
                    .build());
        } catch (Exception e) {
            log.debug("Could not record click (continuing): {}", e.getMessage());
        }

        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, outbound)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-Robots-Tag", "noindex, nofollow")
                .build();
    }

    private static SitePrice pickSite(Product p, String slugOrName, String offerUrl) {
        // Exact offer URL wins — disambiguates between multiple sellers of the
        // same product within one marketplace (e.g. two Daraz storefronts).
        if (offerUrl != null && !offerUrl.isBlank()) {
            for (SitePrice sp : p.getPrices()) {
                if (offerUrl.equals(sp.getProductUrl())) return sp;
            }
        }
        if (slugOrName != null) {
            String needle = slugOrName.toLowerCase();
            for (SitePrice sp : p.getPrices()) {
                if (sp.getSiteSlug() != null && sp.getSiteSlug().equalsIgnoreCase(needle)) return sp;
                if (sp.getSiteName() != null && sp.getSiteName().toLowerCase().contains(needle)) return sp;
            }
        }
        // Default: cheapest in-stock; failing that, just the cheapest.
        SitePrice cheapestInStock = null, cheapest = null;
        for (SitePrice sp : p.getPrices()) {
            if (sp.getPrice() == null) continue;
            if (cheapest == null || sp.getPrice() < cheapest.getPrice()) cheapest = sp;
            if (Boolean.TRUE.equals(sp.getInStock()) &&
                    (cheapestInStock == null || sp.getPrice() < cheapestInStock.getPrice())) {
                cheapestInStock = sp;
            }
        }
        return cheapestInStock != null ? cheapestInStock : cheapest;
    }

    private static String asString(Object v) { return v == null ? null : v.toString(); }

    static String anonIdFrom(Cookie[] cookies, String headerValue) {
        String value = null;
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if ("dk_anon_id".equals(cookie.getName())) {
                    value = cookie.getValue();
                    break;
                }
            }
        }
        if (value == null || value.isBlank()) value = headerValue;
        if (value == null) return null;
        value = value.trim();
        return value.length() > 64 ? value.substring(0, 64) : value;
    }

    private static String clientIp(HttpServletRequest req) {
        String fwd = req.getHeader("X-Forwarded-For");
        if (fwd != null && !fwd.isBlank()) {
            int comma = fwd.indexOf(',');
            return (comma < 0 ? fwd : fwd.substring(0, comma)).trim();
        }
        return req.getRemoteAddr();
    }

    @SuppressWarnings("unused")
    private static URI safeUri(String s) {
        try { return URI.create(s); } catch (Exception e) { return null; }
    }
}
