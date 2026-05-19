package com.damKemon.dam.kemon.scraper;

import com.damKemon.dam.kemon.intelligence.PriceParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;

/**
 * Domain-agnostic product extractor. Tries three signal sources in order:
 *
 * <ol>
 *   <li>JSON-LD blocks of {@code @type: Product} (schema.org)</li>
 *   <li>Open Graph product tags ({@code og:type=product}, {@code og:title},
 *       {@code product:price:amount})</li>
 *   <li>Microdata / meta itemprop fallbacks</li>
 * </ol>
 *
 * <p>If none of the above yield a name + price, returns {@code null}. This
 * is the per-URL filter that decides "is this actually a product page?" —
 * matches the user's "schema.org or OG only" site policy.
 */
@Component
public class GenericProductExtractor extends BaseScraper implements ProductExtractor {

    private static final Logger logger = LoggerFactory.getLogger(GenericProductExtractor.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final BrowserFetcher browser;

    public GenericProductExtractor(BrowserFetcher browser) {
        this.browser = browser;
    }

    @Override public String getSiteName() { return "Generic"; }
    @Override public String getSiteSlug() { return "generic"; }

    /** Always supports — used as fallback when no specific extractor claims a URL. */
    @Override public boolean supports(String url) { return true; }

    @Override
    public ScrapedProduct extract(String url) {
        return extract(url, false);
    }

    @Override
    public ScrapedProduct extract(String url, boolean useJs) {
        Document doc = useJs ? fetchDocWithJs(url) : fetchDoc(url);
        if (doc == null) return null;

        ScrapedProduct fromJsonLd = parseJsonLd(doc);
        if (isValid(fromJsonLd)) return absoluteImage(fromJsonLd, url, doc);

        ScrapedProduct fromOg = parseOpenGraph(doc);
        if (isValid(fromOg)) return absoluteImage(fromOg, url, doc);

        // Microdata / itemprop fallback
        ScrapedProduct fromMicro = parseMicrodata(doc);
        if (isValid(fromMicro)) return absoluteImage(fromMicro, url, doc);

        // CSS-selector fallback for shops that don't expose structured data
        // (covers most stock WooCommerce + Shopify themes).
        ScrapedProduct fromCss = parseCommonCss(doc);
        if (isValid(fromCss)) return absoluteImage(fromCss, url, doc);

        return null;
    }

    /**
     * Try a small set of CSS selectors known from default WooCommerce and
     * Shopify themes. Only invoked when JSON-LD / OG / microdata all miss.
     */
    public ScrapedProduct parseCommonCss(Document doc) {
        Element nameEl = doc.selectFirst(
                "h1.product_title, h1.product-single__title, h1.product-title, "
              + "h1.entry-title, h1[class*=product], h1");
        if (nameEl == null || nameEl.text().isBlank()) return null;
        String name = nameEl.text().trim();

        Double price = null;
        // Try common WooCommerce/Shopify price containers in order.
        for (String sel : new String[]{
                "p.price ins .woocommerce-Price-amount bdi",  // discounted price
                "p.price .woocommerce-Price-amount bdi",
                ".product-price-update",
                "span.price-item--sale",
                "span.price-item--regular",
                "span.price ins",
                "span.price",
                "[data-product-price]",
                ".product-info-price [data-price-amount]",
                "[itemprop=offers] [itemprop=price]"
        }) {
            Element pe = doc.selectFirst(sel);
            if (pe == null) continue;
            String txt = pe.hasAttr("data-product-price") ? pe.attr("data-product-price")
                       : pe.hasAttr("data-price-amount") ? pe.attr("data-price-amount")
                       : pe.hasAttr("content") ? pe.attr("content")
                       : pe.text();
            Double parsed = PriceParser.parseFirst(txt);
            if (parsed != null && parsed > 0) { price = parsed; break; }
        }
        if (price == null) return null;

        String image = null;
        Element imgEl = doc.selectFirst(
                "img.wp-post-image, .woocommerce-product-gallery__image img, "
              + ".product-single__image, .product-image-main img, "
              + "[itemprop=image], img[class*=product]");
        if (imgEl != null) image = imgEl.hasAttr("src") ? imgEl.attr("src") : imgEl.attr("data-src");

        // Common rating + review-count markup. Each shop pattern tried in
        // turn; first hit wins. Skipped silently if none match.
        Double rating = readRating(doc);
        Integer reviewCount = readReviewCount(doc);

        return ScrapedProduct.builder()
                .name(name).price(price).imageUrl(image)
                .rating(rating).reviewCount(reviewCount)
                .inStock(true).build();
    }

    private static Double readRating(Document doc) {
        // schema.org meta on the page (separate from JSON-LD)
        Element m = doc.selectFirst("[itemprop=ratingValue][content], meta[itemprop=ratingValue]");
        if (m != null) {
            Double v = parseFractional(m.hasAttr("content") ? m.attr("content") : m.text());
            if (v != null) return v;
        }
        // WooCommerce star block uses aria-label / title like "Rated 4.5 out of 5"
        for (String sel : new String[]{
                ".star-rating", "[class*=star-rating]", ".woocommerce-product-rating .rating",
                ".spr-badge-starrating", ".jdgm-prev-badge", "[data-rating]"
        }) {
            Element e = doc.selectFirst(sel);
            if (e == null) continue;
            for (String attr : new String[]{"aria-label", "title", "data-rating", "data-score"}) {
                String v = e.attr(attr);
                if (v != null && !v.isBlank()) {
                    Double parsed = parseFractional(v);
                    if (parsed != null) return parsed;
                }
            }
            // Some star blocks encode rating as "width: 90%" inline style → 4.5/5
            Element span = e.selectFirst("span[style*=width]");
            if (span != null) {
                String style = span.attr("style").replaceAll("\\s+", "");
                java.util.regex.Matcher pct = java.util.regex.Pattern.compile("width:(\\d+(?:\\.\\d+)?)%").matcher(style);
                if (pct.find()) {
                    try { return Math.round(Double.parseDouble(pct.group(1)) / 20.0 * 10.0) / 10.0; }
                    catch (NumberFormatException ignored) {}
                }
            }
        }
        return null;
    }

    private static Integer readReviewCount(Document doc) {
        Element m = doc.selectFirst("[itemprop=reviewCount][content], meta[itemprop=reviewCount], [itemprop=ratingCount]");
        if (m != null) {
            try { return Integer.parseInt((m.hasAttr("content") ? m.attr("content") : m.text()).trim()); }
            catch (Exception ignored) {}
        }
        // Common review-count text: "(123 reviews)", "12 customer reviews"
        for (String sel : new String[]{
                ".woocommerce-review-link", ".reviews-count", ".reviewCount",
                ".spr-badge-caption", ".jdgm-prev-badge__text", "[class*=review-count]"
        }) {
            Element e = doc.selectFirst(sel);
            if (e == null) continue;
            java.util.regex.Matcher num = java.util.regex.Pattern.compile("(\\d[\\d,]*)").matcher(e.text());
            if (num.find()) {
                try { return Integer.parseInt(num.group(1).replace(",", "")); }
                catch (NumberFormatException ignored) {}
            }
        }
        return null;
    }

    private static Double parseFractional(String s) {
        if (s == null) return null;
        // "4.5 out of 5", "Rated 4.5 stars", "4.5", "4,5"
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+[.,]?\\d*)").matcher(s);
        if (!m.find()) return null;
        try {
            double v = Double.parseDouble(m.group(1).replace(",", "."));
            // If on a 100-point scale ("90% → 4.5"), rescale.
            if (v > 5 && v <= 100) return Math.round(v / 20.0 * 10.0) / 10.0;
            if (v >= 0 && v <= 5) return Math.round(v * 10.0) / 10.0;
            if (v > 5 && v <= 10) return Math.round(v / 2.0 * 10.0) / 10.0;
        } catch (NumberFormatException ignored) {}
        return null;
    }

    private Document fetchDoc(String url) {
        // Default: jsoup only. Don't auto-escalate to Playwright globally
        // — that would 10× the nightly indexer runtime. SPA shops opt in
        // via fetchDocWithJs(url) called from the JS-aware overload.
        try {
            return fetch(url);
        } catch (Exception e) {
            logger.debug("Generic fetch failed for {}: {}", url, e.getMessage());
            return null;
        }
    }

    private Document fetchDocWithJs(String url) {
        if (browser.isAvailable()) {
            Document d = browser.fetchDocument(url);
            if (d != null) return d;
        }
        // Fall back to jsoup if Playwright is unavailable. Better to get
        // a partial parse than nothing.
        return fetchDoc(url);
    }

    // ---------- JSON-LD ----------
    public ScrapedProduct parseJsonLd(Document doc) {
        Elements scripts = doc.select("script[type=application/ld+json]");
        for (Element s : scripts) {
            String json = s.data();
            if (json == null || json.isBlank()) continue;
            try {
                JsonNode root = MAPPER.readTree(json);
                ScrapedProduct p = findProduct(root);
                if (p != null) return p;
            } catch (Exception e) {
                logger.debug("JSON-LD parse error: {}", e.getMessage());
            }
        }
        return null;
    }

    private ScrapedProduct findProduct(JsonNode node) {
        if (node == null) return null;
        if (node.isArray()) {
            for (JsonNode el : node) {
                ScrapedProduct p = findProduct(el);
                if (p != null) return p;
            }
            return null;
        }
        if (node.isObject()) {
            JsonNode graph = node.get("@graph");
            if (graph != null) {
                ScrapedProduct p = findProduct(graph);
                if (p != null) return p;
            }
            JsonNode type = node.get("@type");
            if (type != null && typeMatchesProduct(type)) {
                return jsonLdToProduct(node);
            }
        }
        return null;
    }

    private boolean typeMatchesProduct(JsonNode type) {
        if (type.isTextual()) return "Product".equalsIgnoreCase(type.asText());
        if (type.isArray()) {
            for (JsonNode t : type) {
                if (t.isTextual() && "Product".equalsIgnoreCase(t.asText())) return true;
            }
        }
        return false;
    }

    private ScrapedProduct jsonLdToProduct(JsonNode node) {
        String name = textOf(node.get("name"));
        if (name == null || name.isBlank()) return null;

        String image = extractImage(node);
        Double rating = null;
        Integer reviewCount = null;
        JsonNode agg = node.get("aggregateRating");
        if (agg != null) {
            rating = asDouble(agg.get("ratingValue"));
            JsonNode rc = agg.get("reviewCount");
            if (rc == null) rc = agg.get("ratingCount");
            if (rc != null && rc.canConvertToInt()) reviewCount = rc.asInt();
        }

        Double price = null;
        Double originalPrice = null;
        Boolean inStock = null;
        JsonNode offers = node.get("offers");
        if (offers != null) {
            JsonNode o = offers.isArray() ? offers.get(0) : offers;
            if (o != null) {
                price = asDouble(o.get("price"));
                if (price == null) price = asDouble(o.get("lowPrice"));
                originalPrice = asDouble(o.get("highPrice"));
                if (originalPrice != null && price != null && originalPrice <= price) originalPrice = null;
                JsonNode avail = o.get("availability");
                if (avail != null && avail.isTextual()) {
                    String a = avail.asText().toLowerCase();
                    inStock = !(a.contains("outofstock") || a.contains("soldout"));
                }
            }
        }

        return ScrapedProduct.builder()
                .name(name.trim())
                .price(price)
                .originalPrice(originalPrice)
                .imageUrl(image)
                .rating(rating)
                .reviewCount(reviewCount)
                .inStock(inStock == null ? Boolean.TRUE : inStock)
                .build();
    }

    private String extractImage(JsonNode node) {
        JsonNode img = node.get("image");
        if (img == null) return null;
        if (img.isTextual()) return img.asText();
        if (img.isArray() && img.size() > 0) {
            JsonNode first = img.get(0);
            if (first.isTextual()) return first.asText();
            if (first.isObject() && first.has("url")) return first.get("url").asText();
        }
        if (img.isObject() && img.has("url")) return img.get("url").asText();
        return null;
    }

    // ---------- OpenGraph product:* ----------
    public ScrapedProduct parseOpenGraph(Document doc) {
        String type = metaContent(doc, "og:type");
        boolean isProduct = type != null && type.toLowerCase().contains("product");
        // If og:type isn't 'product' but we have a price meta, still try.
        String priceText = metaContent(doc, "product:price:amount");
        if (priceText == null) priceText = metaContent(doc, "og:price:amount");
        if (priceText == null && !isProduct) return null;

        String name = metaContent(doc, "og:title");
        if (name == null || name.isBlank()) return null;

        Double price = priceText == null ? null : PriceParser.parseFirst(priceText);
        String image = metaContent(doc, "og:image");
        Boolean inStock = null;
        String avail = metaContent(doc, "product:availability");
        if (avail != null) {
            String a = avail.toLowerCase();
            inStock = !(a.contains("outofstock") || a.contains("oos") || a.contains("sold"));
        }
        return ScrapedProduct.builder()
                .name(name.trim()).price(price).imageUrl(image)
                .inStock(inStock == null ? Boolean.TRUE : inStock).build();
    }

    // ---------- microdata / itemprop ----------
    public ScrapedProduct parseMicrodata(Document doc) {
        Element nameEl = doc.selectFirst("[itemprop=name]");
        Element priceEl = doc.selectFirst("[itemprop=price]");
        if (nameEl == null || priceEl == null) return null;
        String name = nameEl.hasAttr("content") ? nameEl.attr("content") : nameEl.text();
        String priceText = priceEl.hasAttr("content") ? priceEl.attr("content") : priceEl.text();
        if (name == null || name.isBlank()) return null;
        Double price = PriceParser.parseFirst(priceText);
        Element imgEl = doc.selectFirst("[itemprop=image]");
        String image = imgEl == null ? null : (imgEl.hasAttr("content") ? imgEl.attr("content") : imgEl.attr("src"));
        return ScrapedProduct.builder().name(name.trim()).price(price).imageUrl(image).inStock(true).build();
    }

    private static String metaContent(Document doc, String prop) {
        Element m = doc.selectFirst("meta[property=" + prop + "], meta[name=" + prop + "]");
        if (m == null) return null;
        String c = m.attr("content");
        return c == null || c.isBlank() ? null : c;
    }

    private static String textOf(JsonNode n) { return n == null || !n.isValueNode() ? null : n.asText(); }

    private static Double asDouble(JsonNode n) {
        if (n == null) return null;
        if (n.isNumber()) return n.asDouble();
        if (n.isTextual()) return PriceParser.parseFirst(n.asText());
        return null;
    }

    public static boolean isValid(ScrapedProduct p) {
        return p != null && p.getName() != null && !p.getName().isBlank() && p.getPrice() != null && p.getPrice() > 0;
    }

    /** Resolve relative image URLs against the page URL. */
    private static ScrapedProduct absoluteImage(ScrapedProduct p, String pageUrl, Document doc) {
        if (p == null) return null;
        if (p.getProductUrl() == null) p.setProductUrl(pageUrl);
        String img = p.getImageUrl();
        if (img == null || img.startsWith("http")) return p;
        try {
            URI base = URI.create(doc.baseUri().isBlank() ? pageUrl : doc.baseUri());
            p.setImageUrl(base.resolve(img).toString());
        } catch (Exception ignored) {}
        return p;
    }
}
