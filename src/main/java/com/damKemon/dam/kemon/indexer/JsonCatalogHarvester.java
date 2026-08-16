package com.damKemon.dam.kemon.indexer;

import com.damKemon.dam.kemon.model.Shop;
import com.damKemon.dam.kemon.scraper.ScrapedProduct;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.parser.Parser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/**
 * Bulk product harvester for the two storefront platforms that publish their
 * whole catalog as JSON — no Chromium, no sitemap crawl, no per-page fetch.
 *
 * <ul>
 *   <li><b>Shopify</b> → {@code /products.json?limit=250&page=N} (public).</li>
 *   <li><b>WooCommerce</b> → {@code /wp-json/wc/store/v1/products?per_page=100&page=N}
 *       (the unauthenticated Store API).</li>
 * </ul>
 *
 * <p>One paginated JSON request returns hundreds of products for a few KB of
 * memory — an order of magnitude cheaper than rendering/scraping product pages,
 * which is what makes it viable on a small (1 GB) host. Platform tags in
 * shops.json are unreliable (some "wordpress" shops are really Shopify), so for
 * every supported shop we <em>try Shopify first, then WooCommerce</em>, and keep
 * whichever returns products. If neither responds with JSON we return empty and
 * {@link BulkIndexer} falls back to the normal sitemap/page pipeline — so this
 * can only ever add coverage, never remove it.
 *
 * <p>Ordered last so the shop-specific harvesters (Chaldal, Daraz) always claim
 * their own shops first.
 */
@Service
@Order(Ordered.LOWEST_PRECEDENCE)
public class JsonCatalogHarvester implements ShopHarvester {

    private static final Logger log = LoggerFactory.getLogger(JsonCatalogHarvester.class);

    private static final String UA =
            "Mozilla/5.0 (compatible; DamKemonBot/1.0; +https://damkemon.com/bot)";

    /** Platforms worth probing. Chaldal/Daraz are "custom" → owned by their own harvesters. */
    private static final Set<String> PLATFORMS = Set.of("shopify", "wordpress", "woocommerce");

    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${json-harvest.max-products-per-shop:1500}") private int maxProducts;
    @Value("${json-harvest.shopify-page-size:250}")      private int shopifyPageSize;
    @Value("${json-harvest.woo-page-size:100}")          private int wooPageSize;
    @Value("${json-harvest.max-pages:15}")               private int maxPages;
    @Value("${json-harvest.timeout-ms:12000}")          private int timeoutMs;
    @Value("${json-harvest.request-delay-ms:250}")      private long requestDelayMs;

    @Override
    public boolean supports(Shop shop) {
        // Probe ANY shop with a base URL — try Shopify /products.json then the
        // WooCommerce Store API regardless of the (often wrong) platform tag.
        // This harvester is Ordered.LOWEST_PRECEDENCE, so shop-specific harvesters
        // (Chaldal/Daraz/Shopify) still claim their shops first; and when neither
        // JSON endpoint responds harvest() returns empty, so BulkIndexer falls
        // through to the sitemap pipeline. Net effect is additive: it can only
        // rescue a mis-tagged storefront (e.g. a "custom"/"magento" shop that is
        // really Shopify), never remove coverage. PLATFORMS below records the
        // tags we *expect*; it's no longer a gate.
        return shop != null && shop.getBaseUrl() != null && !shop.getBaseUrl().isBlank();
    }

    @Override
    public List<ScrapedProduct> harvest(Shop shop) {
        String base = stripTrailingSlash(shop.getBaseUrl());
        List<ScrapedProduct> out = harvestShopify(base);
        if (!out.isEmpty()) {
            log.info("JSON harvest: shop '{}' → {} products via Shopify products.json", shop.getSlug(), out.size());
            return out;
        }
        out = harvestWooCommerce(base);
        if (!out.isEmpty()) {
            log.info("JSON harvest: shop '{}' → {} products via WooCommerce Store API", shop.getSlug(), out.size());
        }
        return out;
    }

    // ─────────────────────────────── Shopify ───────────────────────────────

    private List<ScrapedProduct> harvestShopify(String base) {
        LinkedHashMap<String, ScrapedProduct> byUrl = new LinkedHashMap<>();
        for (int page = 1; page <= maxPages && byUrl.size() < maxProducts; page++) {
            JsonNode root = getJson(base + "/products.json?limit=" + shopifyPageSize + "&page=" + page);
            JsonNode products = root == null ? null : root.path("products");
            if (products == null || !products.isArray() || products.isEmpty()) break;
            for (JsonNode p : products) {
                if (byUrl.size() >= maxProducts) break;
                ScrapedProduct sp = mapShopify(base, p);
                if (sp != null) byUrl.putIfAbsent(sp.getProductUrl(), sp);
            }
            if (products.size() < shopifyPageSize) break; // last page
        }
        return new ArrayList<>(byUrl.values());
    }

    private ScrapedProduct mapShopify(String base, JsonNode p) {
        String title = text(p, "title");
        String handle = text(p, "handle");
        if (title == null || handle == null) return null;
        JsonNode variants = p.path("variants");
        if (!variants.isArray() || variants.isEmpty()) return null;
        JsonNode v0 = variants.get(0);
        Double price = parseDouble(text(v0, "price"));
        if (price == null || price <= 0) return null;
        Double compare = parseDouble(text(v0, "compare_at_price"));
        return ScrapedProduct.builder()
                .name(title)
                .price(price)
                .originalPrice(compare != null && compare > price ? compare : null)
                .productUrl(base + "/products/" + handle)
                .imageUrl(firstImage(p, "src"))
                .inStock(!v0.path("available").isBoolean() || v0.path("available").asBoolean(true))
                .build();
    }

    // ──────────────────────────── WooCommerce ──────────────────────────────

    private List<ScrapedProduct> harvestWooCommerce(String base) {
        LinkedHashMap<String, ScrapedProduct> byUrl = new LinkedHashMap<>();
        for (int page = 1; page <= maxPages && byUrl.size() < maxProducts; page++) {
            JsonNode arr = getJson(base + "/wp-json/wc/store/v1/products?per_page=" + wooPageSize + "&page=" + page);
            if (arr == null || !arr.isArray() || arr.isEmpty()) break;
            for (JsonNode p : arr) {
                if (byUrl.size() >= maxProducts) break;
                ScrapedProduct sp = mapWoo(p);
                if (sp != null) byUrl.putIfAbsent(sp.getProductUrl(), sp);
            }
            if (arr.size() < wooPageSize) break; // last page
        }
        return new ArrayList<>(byUrl.values());
    }

    private ScrapedProduct mapWoo(JsonNode p) {
        String name = text(p, "name");
        String url = text(p, "permalink");
        if (name == null || url == null) return null;
        name = Parser.unescapeEntities(name, false); // WC names carry &amp; etc.
        JsonNode prices = p.path("prices");
        int minor = prices.path("currency_minor_unit").asInt(0);
        Double price = parseMinor(text(prices, "price"), minor);
        if (price == null || price <= 0) return null;
        Double regular = parseMinor(text(prices, "regular_price"), minor);
        return ScrapedProduct.builder()
                .name(name)
                .price(price)
                .originalPrice(regular != null && regular > price ? regular : null)
                .productUrl(url)
                .imageUrl(firstImage(p, "src"))
                .inStock(!p.path("is_in_stock").isBoolean() || p.path("is_in_stock").asBoolean(true))
                .build();
    }

    // ─────────────────────────────── helpers ───────────────────────────────

    /** GET a URL and parse JSON. Returns null on non-200, non-JSON (HTML fallback), or error. */
    private JsonNode getJson(String url) {
        try {
            Connection.Response res = Jsoup.connect(url)
                    .userAgent(UA)
                    .header("Accept", "application/json")
                    .ignoreContentType(true)
                    .ignoreHttpErrors(true)
                    .followRedirects(true)
                    .timeout(timeoutMs)
                    .maxBodySize(10 * 1024 * 1024)   // cap one response at 10 MB so a giant blob can't OOM the 1 GB box
                    .method(Connection.Method.GET)
                    .execute();
            if (res.statusCode() != 200) return null;
            String body = res.body();
            if (body == null) return null;
            String t = body.stripLeading();
            if (t.isEmpty() || (t.charAt(0) != '[' && t.charAt(0) != '{')) return null; // HTML, not JSON
            sleep(requestDelayMs);
            return mapper.readTree(body);
        } catch (Exception e) {
            log.debug("JSON harvest GET failed for {}: {}", url, e.getMessage());
            return null;
        }
    }

    private static String firstImage(JsonNode p, String field) {
        JsonNode imgs = p.path("images");
        if (imgs.isArray() && !imgs.isEmpty()) {
            String src = text(imgs.get(0), field);
            if (src != null) return src;
        }
        return null;
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return v.isMissingNode() || v.isNull() || v.asText().isBlank() ? null : v.asText().trim();
    }

    private static Double parseDouble(String s) {
        if (s == null) return null;
        try { return Double.parseDouble(s.replaceAll("[^0-9.]", "")); }
        catch (NumberFormatException e) { return null; }
    }

    /** WooCommerce Store API gives prices as integers in minor units (e.g. "250000" + minor_unit 2 = 2500.00). */
    private static Double parseMinor(String raw, int minorUnit) {
        if (raw == null) return null;
        try {
            double v = Double.parseDouble(raw.replaceAll("[^0-9.]", ""));
            return minorUnit > 0 ? v / Math.pow(10, minorUnit) : v;
        } catch (NumberFormatException e) { return null; }
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static void sleep(long ms) {
        if (ms <= 0) return;
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
