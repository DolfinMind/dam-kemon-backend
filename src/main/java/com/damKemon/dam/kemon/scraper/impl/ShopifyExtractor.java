package com.damKemon.dam.kemon.scraper.impl;

import com.damKemon.dam.kemon.intelligence.PriceParser;
import com.damKemon.dam.kemon.scraper.BaseScraper;
import com.damKemon.dam.kemon.scraper.GenericProductExtractor;
import com.damKemon.dam.kemon.scraper.ProductExtractor;
import com.damKemon.dam.kemon.scraper.ScrapedProduct;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Platform-tier extractor for Shopify storefronts. Shopify has the most
 * uniform structure of any e-commerce platform — every product page is
 * also reachable as JSON at {@code <product-url>.json}, which returns the
 * full product object including variants, prices in cents, and image URLs.
 * That JSON endpoint is the primary path; HTML selectors are a fallback
 * for shops that block .json scraping.
 *
 * <p>Covers the Shopify shops in {@code shops.json}: Aarong, Sailor, Yellow,
 * Ecstasy, Anjan's, Le Reve, Cats Eye, Infinity, Twelve Clothing, Shajgoj,
 * Skin Cafe — most BD fashion + beauty retailers.
 */
@Component
public class ShopifyExtractor extends BaseScraper implements ProductExtractor {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Hosts we positively recognise as Shopify-backed BD shops. */
    private static final List<String> SHOPIFY_HOSTS = List.of(
            "aarong.com", "sailorbd.com", "yellowclothing.net", "ecstasy.com.bd",
            "anjans.com", "lereve.com.bd", "catseye.com.bd", "infinity.com.bd",
            "twelve.com.bd", "shop.shajgoj.com", "skincafebd.com"
    );

    private final GenericProductExtractor generic;

    public ShopifyExtractor(GenericProductExtractor generic) {
        this.generic = generic;
    }

    @Override public String getSiteName() { return "Shopify"; }
    @Override public String getSiteSlug() { return "shopify"; }

    @Override
    public boolean supports(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase();
        for (String h : SHOPIFY_HOSTS) if (lower.contains(h)) return true;
        // Generic detection — any /products/<handle> path is a Shopify
        // permalink convention. Other platforms use /product/ (singular)
        // so this rarely false-positives.
        return lower.contains("/products/");
    }

    @Override
    public ScrapedProduct extract(String url) {
        return extract(url, false);
    }

    @Override
    public ScrapedProduct extract(String url, boolean useJs) {
        // 1. The Shopify .json endpoint — fastest + most reliable.
        ScrapedProduct fromJson = tryJsonEndpoint(url);
        if (GenericProductExtractor.isValid(fromJson)) return fromJson;

        Document doc;
        try { doc = fetch(url); }
        catch (Exception e) {
            log.debug("Shopify fetch failed for {}: {}", url, e.getMessage());
            return null;
        }
        if (doc == null) return null;

        // 2. JSON-LD — Shopify themes (Dawn, Debut, Sense) all emit this.
        ScrapedProduct fromLd = generic.parseJsonLd(doc);
        if (GenericProductExtractor.isValid(fromLd)) { fromLd.setProductUrl(url); return fromLd; }

        // 3. Theme-specific selectors — works on Dawn / Debut / Sense.
        ScrapedProduct fromTheme = parseShopifyTheme(doc);
        if (GenericProductExtractor.isValid(fromTheme)) { fromTheme.setProductUrl(url); return fromTheme; }

        // 4. Open Graph fallback.
        ScrapedProduct fromOg = generic.parseOpenGraph(doc);
        if (GenericProductExtractor.isValid(fromOg)) { fromOg.setProductUrl(url); return fromOg; }

        return null;
    }

    /**
     * Try the {@code <product>.json} convention. The response shape is:
     * {@code { "product": { "title", "body_html", "variants": [{"price":
     * "5400.00"}], "image": {"src": "..."} } }}. Prices come back as
     * strings in the shop's currency (BDT for these shops); we convert to
     * Double via PriceParser to handle ৳ / Bengali numerals defensively.
     */
    private ScrapedProduct tryJsonEndpoint(String url) {
        // Strip trailing slash and any existing query/fragment.
        String base = url;
        int q = base.indexOf('?');
        if (q >= 0) base = base.substring(0, q);
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        if (!base.contains("/products/")) return null;

        String jsonUrl = base + ".json";
        try {
            Connection.Response resp = Jsoup.connect(jsonUrl)
                    .userAgent(pickUa())
                    .header("Accept", "application/json")
                    .ignoreContentType(true)
                    .timeout(timeoutMs)
                    .followRedirects(true)
                    .ignoreHttpErrors(true)
                    .execute();
            if (resp.statusCode() != 200) return null;
            String body = resp.body();
            if (body == null || body.isBlank()) return null;
            JsonNode root = MAPPER.readTree(body);
            JsonNode product = root.path("product");
            if (product.isMissingNode() || product.isNull()) return null;

            String title = product.path("title").asText(null);
            JsonNode variants = product.path("variants");
            Double price = null, original = null;
            boolean inStock = true;
            if (variants.isArray() && variants.size() > 0) {
                JsonNode v0 = variants.get(0);
                price = PriceParser.parseFirst(v0.path("price").asText(null));
                String comp = v0.path("compare_at_price").asText(null);
                if (comp != null && !comp.equals("null") && !comp.isBlank()) {
                    original = PriceParser.parseFirst(comp);
                }
                String avail = v0.path("available").asText("true");
                inStock = !"false".equalsIgnoreCase(avail);
            }
            if (title == null || price == null) return null;

            String image = null;
            JsonNode img = product.path("image");
            if (!img.isMissingNode() && !img.isNull()) {
                image = img.path("src").asText(null);
            }
            if (image == null) {
                JsonNode imgs = product.path("images");
                if (imgs.isArray() && imgs.size() > 0) image = imgs.get(0).path("src").asText(null);
            }

            return ScrapedProduct.builder()
                    .name(title.trim())
                    .price(price)
                    .originalPrice(original)
                    .imageUrl(image)
                    .inStock(inStock)
                    .productUrl(url)
                    .build();
        } catch (Exception e) {
            log.debug("Shopify .json endpoint failed for {}: {}", jsonUrl, e.getMessage());
            return null;
        }
    }

    private ScrapedProduct parseShopifyTheme(Document doc) {
        Element title = doc.selectFirst(
                "h1.product__title, .product__title h1, "
              + "h1.product-single__title, .product-single__title h1, "
              + "[data-product-title], .product-title h1, h1.h1, h1");
        if (title == null) return null;

        Element priceEl = doc.selectFirst(
                "[data-product-price], .price__current, .product__price, "
              + ".price-item--sale, .price-item--regular, "
              + ".product-single__price, .product__price-item, "
              + "span.money, .price .money");
        if (priceEl == null) return null;

        Double price = PriceParser.parseFirst(priceEl.text());
        if (price == null) return null;

        Element compareEl = doc.selectFirst(
                ".price__compare, .price-item--compare-at, "
              + ".product-single__price--was, .compare-at-price, "
              + "s.product__price, s.price");
        Double compare = compareEl == null ? null : PriceParser.parseFirst(compareEl.text());

        Element img = doc.selectFirst(
                ".product__media img, .product-single__photo img, "
              + ".product-featured-img, .product__photo img, "
              + ".product-image img, img[itemprop=image]");
        String imgUrl = img == null ? null : img.attr("abs:src");
        if ((imgUrl == null || imgUrl.isBlank()) && img != null) {
            if (!img.attr("abs:data-src").isBlank())   imgUrl = img.attr("abs:data-src");
            else if (!img.attr("abs:data-srcset").isBlank()) {
                imgUrl = img.attr("abs:data-srcset").split(",")[0].trim().split("\\s+")[0];
            }
        }

        Element soldOut = doc.selectFirst(".sold-out, .product__sold-out, .price--sold-out");
        boolean inStock = soldOut == null;

        return ScrapedProduct.builder()
                .name(title.text().trim())
                .price(price)
                .originalPrice(compare)
                .imageUrl(imgUrl)
                .inStock(inStock)
                .build();
    }

    private String pickUa() {
        if (userAgents == null || userAgents.isEmpty()) return "Mozilla/5.0";
        return userAgents.get(0);
    }
}
