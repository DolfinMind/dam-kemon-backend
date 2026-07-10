package com.damKemon.dam.kemon.controller;

import com.damKemon.dam.kemon.model.Product;
import com.damKemon.dam.kemon.service.ProductService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;

/**
 * Minimal HTML shell for social link previews of {@code /product/*}.
 *
 * <p>The SPA serves one static head, so Facebook / WhatsApp / Messenger /
 * Twitter / Telegram previews of a shared product link only ever showed the
 * generic site card. The reverse proxy routes those user-agents' requests for
 * {@code /product/*} here instead (same path, like {@code /sitemap.xml});
 * everyone else still gets the SPA. See {@code ops/nginx-social-preview.conf}.
 *
 * <p>Not SSR — just OG meta tags plus a meta-refresh so a human who lands
 * here directly bounces to the real page.
 */
@RestController
public class SharePreviewController {

    private static final MediaType TEXT_HTML_UTF8 = new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8);

    private final ProductService productService;

    // Same default-to-prod-domain rule as SitemapController: prod never set
    // AUTH_WEB_URL, and og:url/og:image must be absolute public URLs.
    @Value("${auth.web-url:https://damkemon.com}")
    private String webUrl;

    public SharePreviewController(ProductService productService) {
        this.productService = productService;
    }

    @GetMapping(value = "/product/{idOrSlug}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> productPreview(@PathVariable String idOrSlug) {
        String base = webUrl.replaceAll("/$", "");
        Optional<Product> p = productService.findByIdOrSlug(idOrSlug);
        // Unknown product: still 200 with the generic site card, so a stale
        // shared link previews as Damkemon instead of a crawler error.
        String html = p.map(product -> render(product, base))
                .orElseGet(() -> renderFallback(base));
        return ResponseEntity.ok()
                .contentType(TEXT_HTML_UTF8)
                .cacheControl(org.springframework.http.CacheControl.maxAge(java.time.Duration.ofHours(1)).cachePublic())
                .body(html);
    }

    /** Package-private for the unit test. */
    static String render(Product product, String base) {
        String name = product.getName() == null ? "Product" : product.getName();
        String slugOrId = (product.getSlug() == null || product.getSlug().isBlank())
                ? product.getId() : product.getSlug();
        String pageUrl = base + "/product/" + urlPath(slugOrId);

        // og:image = the product photo; fall back to the server-rendered
        // branded 1200×630 PNG (OgImageController) when there's no usable one.
        String imageUrl = product.getImageUrl();
        boolean ownImage = imageUrl == null || !imageUrl.startsWith("http");
        if (ownImage) imageUrl = base + "/api/og/product/" + urlPath(slugOrId) + ".png";

        String description = description(product);

        StringBuilder sb = new StringBuilder(1024);
        sb.append("<!doctype html>\n<html lang=\"en\">\n<head>\n<meta charset=\"utf-8\">\n");
        sb.append("<title>").append(escape(name)).append(" | Damkemon</title>\n");
        sb.append("<meta name=\"description\" content=\"").append(escape(description)).append("\">\n");
        sb.append("<meta property=\"og:type\" content=\"website\">\n");
        sb.append("<meta property=\"og:site_name\" content=\"Damkemon\">\n");
        sb.append("<meta property=\"og:url\" content=\"").append(escape(pageUrl)).append("\">\n");
        sb.append("<meta property=\"og:title\" content=\"").append(escape(name)).append("\">\n");
        sb.append("<meta property=\"og:description\" content=\"").append(escape(description)).append("\">\n");
        sb.append("<meta property=\"og:image\" content=\"").append(escape(imageUrl)).append("\">\n");
        if (ownImage) {
            sb.append("<meta property=\"og:image:width\" content=\"1200\">\n");
            sb.append("<meta property=\"og:image:height\" content=\"630\">\n");
        }
        sb.append("<meta name=\"twitter:card\" content=\"summary_large_image\">\n");
        sb.append("<meta http-equiv=\"refresh\" content=\"0;url=").append(escape(pageUrl)).append("\">\n");
        sb.append("</head>\n<body>\n");
        sb.append("<h1>").append(escape(name)).append("</h1>\n");
        sb.append("<p>").append(escape(description)).append("</p>\n");
        sb.append("<p><a href=\"").append(escape(pageUrl)).append("\">See prices on Damkemon</a></p>\n");
        sb.append("</body>\n</html>\n");
        return sb.toString();
    }

    /** Generic site card for unknown ids — same look as the static SPA head. */
    static String renderFallback(String base) {
        Product generic = new Product();
        generic.setName("Damkemon — compare prices across Bangladesh");
        generic.setId("");
        generic.setImageUrl(base + "/og.png");
        return render(generic, base);
    }

    private static String description(Product p) {
        int sellers = p.getPrices() == null ? 0 : p.getPrices().size();
        if (p.getLowestPrice() == null) {
            return "Compare prices from shops across Bangladesh and pick the best deal.";
        }
        String price = "৳" + String.format(Locale.US, "%,d", Math.round(p.getLowestPrice()));
        return sellers > 1
                ? "Best price " + price + " from " + sellers + " sellers in Bangladesh. Compare side by side before you buy."
                : "Best price " + price + " in Bangladesh. Compare prices side by side before you buy.";
    }

    /** Escape for HTML text and attribute values (same table as SitemapController). */
    private static String escape(String s) {
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    /** Slugs are URL-safe already; encode anything else (legacy ids, stray chars). */
    private static String urlPath(String s) {
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
