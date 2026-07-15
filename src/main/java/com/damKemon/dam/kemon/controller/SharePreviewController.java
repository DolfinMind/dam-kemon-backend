package com.damKemon.dam.kemon.controller;

import com.damKemon.dam.kemon.model.Product;
import com.damKemon.dam.kemon.model.SitePrice;
import com.damKemon.dam.kemon.service.ProductService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Minimal crawler-readable product page for {@code /product/*}.
 *
 * <p>The SPA serves one static head, so Facebook / WhatsApp / Messenger /
 * Twitter / Telegram previews of a shared product link only ever showed the
 * generic site card, while search crawlers had to wait for the whole SPA before
 * seeing a title, canonical or structured data. The reverse proxy routes those
 * crawler user-agents here; everyone else still gets the richer SPA. See
 * {@code ops/nginx-social-preview.conf}.
 *
 * <p>This deliberately renders only information that is also visible in the
 * SPA, so it improves discovery without presenting crawlers a different offer.
 */
@RestController
public class SharePreviewController {

    private static final MediaType TEXT_HTML_UTF8 = new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8);
    private static final Pattern PRICE_SUFFIX = Pattern.compile(
            "\\s*[-–—|,:(]*\\s*(?:best\\s+|latest\\s+)?price\\s+in\\s+(?:bangladesh|bd)\\b[^)]*\\)?\\s*$",
            Pattern.CASE_INSENSITIVE);

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
        if (p.isEmpty()) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.NOT_FOUND)
                    .contentType(TEXT_HTML_UTF8)
                    .header("X-Robots-Tag", "noindex, nofollow")
                    .header("Vary", "User-Agent")
                    .body(renderFallback(base));
        }
        return ResponseEntity.ok()
                .contentType(TEXT_HTML_UTF8)
                .cacheControl(org.springframework.http.CacheControl.maxAge(java.time.Duration.ofHours(1)).cachePublic())
                .header("Vary", "User-Agent")
                .body(render(p.get(), base));
    }

    /** Package-private for the unit test. */
    static String render(Product product, String base) {
        String name = cleanName(product.getName());
        String slugOrId = (product.getSlug() == null || product.getSlug().isBlank())
                ? product.getId() : product.getSlug();
        String pageUrl = base + "/product/" + urlPath(slugOrId);

        // og:image = the product photo; fall back to the server-rendered
        // branded 1200×630 PNG (OgImageController) when there's no usable one.
        String imageUrl = product.getImageUrl();
        boolean ownImage = imageUrl == null || !imageUrl.startsWith("http");
        if (ownImage) imageUrl = base + "/api/og/product/" + urlPath(slugOrId) + ".png";

        String description = description(product);
        String priceSuffix = product.getLowestPrice() == null
                ? "" : " — from " + formatPrice(product.getLowestPrice());
        String title = name + " Price in Bangladesh" + priceSuffix + " | Damkemon";

        StringBuilder sb = new StringBuilder(2048);
        sb.append("<!doctype html>\n<html lang=\"en\">\n<head>\n<meta charset=\"utf-8\">\n");
        sb.append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">\n");
        sb.append("<title>").append(escape(title)).append("</title>\n");
        sb.append("<meta name=\"description\" content=\"").append(escape(description)).append("\">\n");
        sb.append("<meta name=\"robots\" content=\"index,follow,max-image-preview:large\">\n");
        sb.append("<link rel=\"canonical\" href=\"").append(escape(pageUrl)).append("\">\n");
        sb.append("<meta property=\"og:type\" content=\"product\">\n");
        sb.append("<meta property=\"og:site_name\" content=\"Damkemon\">\n");
        sb.append("<meta property=\"og:url\" content=\"").append(escape(pageUrl)).append("\">\n");
        sb.append("<meta property=\"og:title\" content=\"").append(escape(title)).append("\">\n");
        sb.append("<meta property=\"og:description\" content=\"").append(escape(description)).append("\">\n");
        sb.append("<meta property=\"og:image\" content=\"").append(escape(imageUrl)).append("\">\n");
        if (ownImage) {
            sb.append("<meta property=\"og:image:width\" content=\"1200\">\n");
            sb.append("<meta property=\"og:image:height\" content=\"630\">\n");
        }
        sb.append("<meta name=\"twitter:card\" content=\"summary_large_image\">\n");
        sb.append("<script type=\"application/ld+json\">").append(structuredData(product, name, pageUrl, imageUrl)).append("</script>\n");
        sb.append("</head>\n<body>\n");
        sb.append("<h1>").append(escape(name)).append("</h1>\n");
        sb.append("<p>").append(escape(description)).append("</p>\n");
        appendOffers(sb, product);
        sb.append("<p><a href=\"").append(escape(pageUrl)).append("\">See prices on Damkemon</a></p>\n");
        sb.append("</body>\n</html>\n");
        return sb.toString();
    }

    /** Generic site card for unknown ids — same look as the static SPA head. */
    static String renderFallback(String base) {
        return "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
                + "<title>Product not found | Damkemon</title>"
                + "<meta name=\"robots\" content=\"noindex,nofollow\">"
                + "<link rel=\"canonical\" href=\"" + escape(base) + "/\"></head>"
                + "<body><h1>Product not found</h1><p><a href=\"" + escape(base)
                + "/\">Compare prices on Damkemon</a></p></body></html>";
    }

    private static String description(Product p) {
        int sellers = p.getPrices() == null ? 0 : p.getPrices().size();
        if (p.getLowestPrice() == null) {
            return "Compare prices from shops across Bangladesh and pick the best deal.";
        }
        String price = formatPrice(p.getLowestPrice());
        return sellers > 1
                ? "Best price " + price + " from " + sellers + " sellers in Bangladesh. Compare side by side before you buy."
                : "Best price " + price + " in Bangladesh. Compare prices side by side before you buy.";
    }

    private static String cleanName(String value) {
        if (value == null || value.isBlank()) return "Product";
        String cleaned = PRICE_SUFFIX.matcher(value).replaceFirst("").replaceAll("\\s{2,}", " ").trim();
        return cleaned.isBlank() ? value.trim() : cleaned;
    }

    private static String formatPrice(Double value) {
        return "৳" + String.format(Locale.US, "%,d", Math.round(value));
    }

    private static void appendOffers(StringBuilder sb, Product product) {
        if (product.getPrices() == null) return;
        List<SitePrice> offers = new ArrayList<>(product.getPrices());
        offers.removeIf(p -> p == null || p.getPrice() == null || p.getPrice() <= 0);
        offers.sort(Comparator.comparing(SitePrice::getPrice));
        if (offers.isEmpty()) return;
        sb.append("<h2>Current prices</h2>\n<ul>\n");
        for (SitePrice offer : offers.subList(0, Math.min(10, offers.size()))) {
            String seller = offer.getSiteName() == null ? "Seller" : offer.getSiteName();
            sb.append("<li>").append(escape(seller)).append(": ")
                    .append(escape(formatPrice(offer.getPrice()))).append("</li>\n");
        }
        sb.append("</ul>\n");
    }

    private static String structuredData(Product p, String name, String pageUrl, String imageUrl) {
        StringBuilder json = new StringBuilder(512);
        json.append("{\"@context\":\"https://schema.org\",\"@type\":\"Product\"")
                .append(",\"name\":\"").append(json(name)).append("\"")
                .append(",\"url\":\"").append(json(pageUrl)).append("\"")
                .append(",\"image\":\"").append(json(imageUrl)).append("\"");
        if (p.getLowestPrice() != null) {
            int count = p.getPrices() == null ? 0 : p.getPrices().size();
            long high = Math.round(p.getHighestPrice() == null ? p.getLowestPrice() : p.getHighestPrice());
            json.append(",\"offers\":{\"@type\":\"AggregateOffer\",\"priceCurrency\":\"BDT\"")
                    .append(",\"lowPrice\":").append(Math.round(p.getLowestPrice()))
                    .append(",\"highPrice\":").append(high)
                    .append(",\"offerCount\":").append(count).append('}');
        }
        return json.append('}').toString();
    }

    private static String json(String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("<", "\\u003c")
                .replace(">", "\\u003e")
                .replace("&", "\\u0026");
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
