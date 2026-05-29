package com.damKemon.dam.kemon.indexer;

import com.damKemon.dam.kemon.intelligence.PriceParser;
import com.damKemon.dam.kemon.model.Shop;
import com.damKemon.dam.kemon.scraper.BrowserFetcher;
import com.damKemon.dam.kemon.scraper.ScrapedProduct;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * The self-discovering scraper. Instead of hand-coding a harvester for every
 * SPA marketplace (Chaldal, Daraz, …), this watches a shop render in a real
 * browser, captures the JSON it loads over the network, and <b>auto-detects the
 * response that holds a product list</b> — then harvests it. Zero per-shop code.
 *
 * <p>How the detection works: it walks every captured JSON body, looks for the
 * richest array whose elements are "product-shaped" (an object carrying a
 * name/title-ish string <i>and</i> a plausible price-ish number), and maps those
 * to {@link ScrapedProduct}s. The page's framework, routing, or markup is
 * irrelevant — if the shop's own frontend loads its products as JSON, the engine
 * finds and reads that exact feed.
 *
 * <p>{@link BulkIndexer} calls this as a last resort when a shop's normal
 * pipeline extracts nothing, so it only ever helps — never regresses — and it's
 * gated on {@code sniffer.enabled} + an available browser.
 */
@Service
public class ApiSniffer {

    private static final Logger log = LoggerFactory.getLogger(ApiSniffer.class);

    private static final String[] NAME_KEYS  = {"name", "title", "productname", "product_name", "itemname", "name_en", "displayname", "label"};
    private static final String[] PRICE_KEYS = {"price", "saleprice", "sale_price", "specialprice", "special_price", "currentprice", "current_price", "finalprice", "final_price", "unitprice", "offerprice", "priceshow", "sellingprice", "mrp", "amount"};
    private static final String[] IMG_KEYS   = {"image", "imageurl", "image_url", "img", "thumbnail", "thumb", "picture", "photo", "imagepath", "featured_image"};
    private static final String[] URL_KEYS   = {"url", "producturl", "product_url", "link", "permalink", "slug", "handle", "href"};

    @Value("${sniffer.enabled:true}")
    private boolean enabled;

    @Value("${sniffer.min-products:4}")
    private int minProducts;

    /** Guard against pathological JSON blobs — cap nodes visited per body. */
    @Value("${sniffer.max-nodes:60000}")
    private int maxNodes;

    /** Per-shop cooldown so a full nightly run can't queue dozens of serial renders. */
    @Value("${sniffer.throttle-hours:24}")
    private long throttleHours;

    private final java.util.concurrent.ConcurrentHashMap<String, Long> lastSniff = new java.util.concurrent.ConcurrentHashMap<>();

    private final BrowserFetcher browser;
    private final ObjectMapper mapper = new ObjectMapper();

    public ApiSniffer(BrowserFetcher browser) {
        this.browser = browser;
    }

    public boolean isEnabled() {
        return enabled && browser.isAvailable();
    }

    /**
     * Render the shop's listing page, watch its network, and return the product
     * list it loaded — or empty if no product feed was found.
     */
    public List<ScrapedProduct> sniff(Shop shop) {
        if (!isEnabled() || shop == null) return List.of();
        // Per-shop cooldown so a full nightly run never queues dozens of serial
        // Playwright renders. Recorded even on failure, so a shop that can't be
        // sniffed isn't retried for the window.
        long now = System.currentTimeMillis();
        Long last = lastSniff.get(shop.getSlug());
        if (last != null && now - last < throttleHours * 3_600_000L) return List.of();
        lastSniff.put(shop.getSlug(), now);

        String pageUrl = listingUrl(shop);
        if (pageUrl == null) return List.of();

        List<BrowserFetcher.JsonCapture> captures = browser.captureJson(pageUrl, 40, 400);
        if (captures.isEmpty()) return List.of();

        String baseUrl = shop.getBaseUrl();
        List<ScrapedProduct> best = List.of();
        String bestEndpoint = null;
        for (BrowserFetcher.JsonCapture cap : captures) {
            List<ScrapedProduct> found = richestProductArray(cap.body(), baseUrl);
            if (found.size() > best.size()) { best = found; bestEndpoint = cap.url(); }
        }

        if (best.size() >= minProducts) {
            log.info("ApiSniffer: shop '{}' — discovered product feed {} → {} products (no per-shop code)",
                    shop.getSlug(), trim(bestEndpoint), best.size());
            return best;
        }
        log.info("ApiSniffer: shop '{}' — scanned {} JSON responses, no product feed found", shop.getSlug(), captures.size());
        return List.of();
    }

    /** Search a page that returns a product LIST (search results, else homepage). */
    private String listingUrl(Shop shop) {
        String tpl = shop.getSearchUrlTemplate();
        if (tpl != null && tpl.contains("{q}")) {
            // A broad term most BD shops return many results for.
            return tpl.replace("{q}", "shirt");
        }
        return shop.getBaseUrl();
    }

    /** Walk the whole JSON tree; return the largest array of product-shaped objects. */
    List<ScrapedProduct> richestProductArray(String json, String baseUrl) {
        JsonNode root;
        try { root = mapper.readTree(json); }
        catch (Exception e) { return List.of(); }

        List<ScrapedProduct> best = new ArrayList<>();
        Deque<JsonNode> stack = new ArrayDeque<>();
        stack.push(root);
        int visited = 0;
        while (!stack.isEmpty() && visited < maxNodes) {
            JsonNode n = stack.pop();
            visited++;
            if (n.isArray()) {
                List<ScrapedProduct> mapped = mapProductArray(n, baseUrl);
                if (mapped.size() > best.size()) best = mapped;
                for (JsonNode c : n) if (c.isContainerNode()) stack.push(c);
            } else if (n.isObject()) {
                for (JsonNode c : n) if (c.isContainerNode()) stack.push(c);
            }
        }
        return best;
    }

    /** Map an array to products if a clear majority of elements are product-shaped. */
    private List<ScrapedProduct> mapProductArray(JsonNode arr, String baseUrl) {
        if (arr.size() < 2) return List.of();
        List<ScrapedProduct> out = new ArrayList<>();
        int objects = 0;
        for (JsonNode el : arr) {
            if (!el.isObject()) continue;
            objects++;
            ScrapedProduct sp = mapProduct(el, baseUrl);
            if (sp != null) out.add(sp);
        }
        // Require a real array of objects, mostly product-shaped — filters out
        // arrays of filters, breadcrumbs, images, config, etc.
        if (objects < 2 || out.size() < 2 || out.size() * 2 < objects) return List.of();
        return out;
    }

    /** A product = an object with a name-ish string AND a plausible price-ish number. */
    private ScrapedProduct mapProduct(JsonNode o, String baseUrl) {
        String name = firstString(o, NAME_KEYS);
        if (name == null || name.length() < 3 || name.length() > 300) return null;
        Double price = firstPrice(o, PRICE_KEYS);
        if (price == null || price < 1 || price > 10_000_000) return null;
        return ScrapedProduct.builder()
                .name(name)
                .price(price)
                .imageUrl(absolutize(firstString(o, IMG_KEYS), baseUrl))
                .productUrl(absolutize(firstString(o, URL_KEYS), baseUrl))
                .inStock(true)
                .build();
    }

    // ---- field readers (case-insensitive key match, one level of nesting) ----

    private static String firstString(JsonNode o, String[] keys) {
        for (String k : keys) {
            JsonNode v = getCi(o, k);
            if (v != null && v.isTextual() && !v.asText().isBlank()) return v.asText().trim();
        }
        return null;
    }

    private static Double firstPrice(JsonNode o, String[] keys) {
        for (String k : keys) {
            JsonNode v = getCi(o, k);
            if (v == null) continue;
            if (v.isNumber()) return v.asDouble();
            if (v.isTextual()) {
                Double p = PriceParser.parseFirst(v.asText());
                if (p != null) return p;
            }
            if (v.isObject()) { // e.g. price: { amount: 600, currency: "BDT" }
                for (String inner : new String[]{"amount", "value", "min", "current"}) {
                    JsonNode iv = getCi(v, inner);
                    if (iv != null && iv.isNumber()) return iv.asDouble();
                    if (iv != null && iv.isTextual()) {
                        Double p = PriceParser.parseFirst(iv.asText());
                        if (p != null) return p;
                    }
                }
            }
        }
        return null;
    }

    private static JsonNode getCi(JsonNode o, String key) {
        JsonNode direct = o.get(key);
        if (direct != null) return direct;
        var it = o.fieldNames();
        while (it.hasNext()) {
            String f = it.next();
            if (f.equalsIgnoreCase(key)) return o.get(f);
        }
        return null;
    }

    private static String absolutize(String v, String baseUrl) {
        if (v == null || v.isBlank()) return null;
        if (v.startsWith("http://") || v.startsWith("https://")) return v;
        if (v.startsWith("//")) return "https:" + v;
        if (baseUrl == null) return v;
        try {
            String root = URI.create(baseUrl).resolve("/").toString().replaceAll("/$", "");
            return root + (v.startsWith("/") ? v : "/" + v);
        } catch (Exception e) { return v; }
    }

    private static String trim(String s) {
        if (s == null) return "?";
        return s.length() > 80 ? s.substring(0, 80) + "…" : s;
    }
}
