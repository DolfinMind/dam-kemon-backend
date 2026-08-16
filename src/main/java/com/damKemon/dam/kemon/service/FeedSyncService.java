package com.damKemon.dam.kemon.service;

import com.damKemon.dam.kemon.config.AppRole;
import com.damKemon.dam.kemon.indexer.BulkIndexer;
import com.damKemon.dam.kemon.model.Shop;
import com.damKemon.dam.kemon.repository.ShopRepository;
import com.damKemon.dam.kemon.scraper.ScrapedProduct;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pulls merchant-published product feeds and merges them into the catalog — the
 * non-scraping way to grow products + sellers. Supports Shopify {@code products.json}
 * (and any JSON array of products) and Google-Merchant / RSS XML feeds. Items flow
 * through {@link BulkIndexer#enrich} — the same cross-shop matchKey merge the
 * crawler uses — so a feed offer for a product we already have attaches as another
 * seller (depth), and a new one inserts (breadth).
 *
 * <p>Heavy merge path → the scheduled run is <b>worker-only and opt-in</b>
 * ({@code feed-sync.enabled=true}); it never auto-runs on the web JVM. The admin
 * per-shop trigger is bounded (one feed) for ad-hoc testing.
 */
@Service
public class FeedSyncService {

    private static final Logger log = LoggerFactory.getLogger(FeedSyncService.class);
    private static final Pattern NUM = Pattern.compile("[0-9][0-9,]*(?:\\.[0-9]+)?");

    private final ShopRepository shops;
    private final BulkIndexer bulkIndexer;
    private final AppRole appRole;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @Value("${feed-sync.enabled:false}")
    private boolean enabled;

    @Value("${feed-sync.max-items:2000}")
    private int maxItems = 2000;   // field default so the parser works under a plain unit test too

    public FeedSyncService(ShopRepository shops, BulkIndexer bulkIndexer, AppRole appRole) {
        this.shops = shops;
        this.bulkIndexer = bulkIndexer;
        this.appRole = appRole;
    }

    @Scheduled(cron = "${feed-sync.cron:0 30 2 * * *}")
    public void scheduled() {
        if (!enabled || !appRole.isWorker()) return;   // heavy merge → worker only, opt-in
        log.info("Feed sync (scheduled): {}", syncAll());
    }

    /** Sync every shop that has a feedUrl. Heavy — intended for the worker. */
    public Map<String, Object> syncAll() {
        List<Shop> withFeed = new ArrayList<>();
        try {
            for (Shop s : shops.findAll()) {
                if (hasFeed(s) && !"blocked".equalsIgnoreCase(s.getStatus())) withFeed.add(s);
            }
        } catch (Exception e) {
            return Map.of("error", e.getMessage());
        }
        if (withFeed.isEmpty()) return Map.of("shops", 0, "persisted", 0, "message", "No shops have a feedUrl yet.");

        BulkIndexer.EnrichSession session = bulkIndexer.openEnrichSession();
        int persisted = 0, ok = 0;
        for (Shop s : withFeed) {
            int n = syncOne(s, session);
            if (n >= 0) { ok++; persisted += n; }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("shops", withFeed.size());
        out.put("synced", ok);
        out.put("persisted", persisted);
        return out;
    }

    /** Sync one shop's feed (bounded) — the admin ad-hoc trigger. */
    public Map<String, Object> syncShop(String slug) {
        Shop s;
        try { s = shops.findBySlug(slug).orElse(null); }
        catch (Exception e) { return Map.of("error", e.getMessage()); }
        if (s == null) return Map.of("error", "shop not found");
        if (!hasFeed(s)) return Map.of("error", "shop has no feedUrl");
        int n = syncOne(s, bulkIndexer.openEnrichSession());
        return n < 0 ? Map.of("slug", slug, "error", "feed fetch/parse failed")
                     : Map.of("slug", slug, "persisted", n);
    }

    private int syncOne(Shop shop, BulkIndexer.EnrichSession session) {
        try {
            List<ScrapedProduct> items = fetchAllPages(shop.getFeedUrl(), shop);
            if (items == null) return -1;
            if (items.isEmpty()) {
                log.info("Feed sync: {} returned 0 parseable items", shop.getSlug());
                return 0;
            }
            int n = bulkIndexer.enrich(session, shop, items);
            log.info("Feed sync: {} — {} items parsed, {} persisted", shop.getSlug(), items.size(), n);
            return n;
        } catch (Exception e) {
            log.warn("Feed sync: {} failed — {}", shop.getSlug(), e.getMessage());
            return -1;
        }
    }

    /**
     * Fetch every page of a paginated JSON feed (Shopify {@code products.json} +
     * Woo Store API both page via {@code &page=N}; defaults are tiny — 10–30/page —
     * so a single fetch gets almost nothing). XML feeds are one file → single fetch.
     * Stops at the first empty page or {@code maxItems}. Returns null only if the
     * very first fetch failed (so the caller can mark the shop dead vs. empty).
     */
    private List<ScrapedProduct> fetchAllPages(String url, Shop shop) throws Exception {
        boolean paged = url.contains("products.json") || url.contains("wp-json");
        if (!paged) {                                   // XML / GMC: one document
            String body = fetch(url);
            return body == null ? null : parse(body, shop);
        }
        String sep = url.contains("?") ? "&" : "?";
        String perPage = url.contains("wp-json") ? "per_page=100" : "limit=250";
        List<ScrapedProduct> out = new ArrayList<>();
        for (int page = 1; page <= 100 && out.size() < maxItems; page++) {
            String body = fetch(url + sep + perPage + "&page=" + page);
            if (body == null) { if (page == 1) return null; break; }
            List<ScrapedProduct> items = parse(body, shop);
            if (items.isEmpty()) break;                 // past the last page
            out.addAll(items);
        }
        return out;
    }

    private static boolean hasFeed(Shop s) {
        return s.getFeedUrl() != null && !s.getFeedUrl().isBlank();
    }

    private String fetch(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", "Mozilla/5.0 (compatible; DamKemonBot/1.0; +https://damkemon.com/bot)")
                .GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() / 100 != 2) {
            log.warn("Feed fetch {} → HTTP {}", url, resp.statusCode());
            return null;
        }
        return resp.body();
    }

    // ── parsing (package-private for the self-check) ─────────────────────────────

    List<ScrapedProduct> parse(String body, Shop shop) {
        if (body == null) return List.of();
        String t = body.trim();
        if (t.isEmpty()) return List.of();
        char c = t.charAt(0);
        if (c == '{' || c == '[') {
            // WooCommerce Store API (/wp-json/wc/store/products) is a bare array whose
            // items carry a `prices` object — distinct from Shopify's {products:[{variants}]}.
            // It's the most common BD platform, so sniff and route to the right parser.
            if (looksLikeWoo(t)) return parseWoo(t, shop);
            return parseShopify(t, shop);
        }
        if (c == '<') return parseXml(t);
        return List.of();
    }

    /** WooCommerce Store API fingerprint — `currency_minor_unit` is unique to it. */
    private static boolean looksLikeWoo(String json) {
        return json.contains("\"currency_minor_unit\"")
                || (json.contains("\"prices\"") && json.contains("\"permalink\""));
    }

    /**
     * WooCommerce Store API: bare array of products with {@code name} +
     * {@code prices{price, currency_minor_unit}}. <b>Price is in MINOR units</b>
     * (e.g. {@code "199000"} with minor_unit 2 → ৳1990.00), so divide by
     * 10^minor_unit — getting this wrong inflates every price 100×.
     */
    List<ScrapedProduct> parseWoo(String json, Shop shop) {
        List<ScrapedProduct> out = new ArrayList<>();
        try {
            JsonNode tree = mapper.readTree(json);
            JsonNode arr = tree.isArray() ? tree : tree.get("products");
            if (arr == null || !arr.isArray()) return out;
            for (JsonNode p : arr) {
                if (out.size() >= maxItems) break;
                String name = text(p, "name");
                JsonNode prices = p.get("prices");
                if (name == null || prices == null || prices.isNull()) continue;
                Double price = wooPrice(prices);
                if (price == null) continue;
                boolean inStock = !p.has("is_in_stock") || p.get("is_in_stock").asBoolean(true);
                out.add(ScrapedProduct.builder()
                        .name(name).price(price).inStock(inStock)
                        .productUrl(text(p, "permalink"))
                        .imageUrl(wooImage(p))
                        .build());
            }
        } catch (Exception e) {
            log.warn("Feed sync: WooCommerce JSON parse failed — {}", e.getMessage());
        }
        return out;
    }

    /** Woo prices are integer minor units; divide by 10^currency_minor_unit. */
    static Double wooPrice(JsonNode prices) {
        Double v = num(text(prices, "price"));
        if (v == null) return null;
        int minor = prices.has("currency_minor_unit") ? prices.get("currency_minor_unit").asInt(0) : 0;
        for (int i = 0; i < minor; i++) v /= 10.0;
        return v;
    }

    private static String wooImage(JsonNode p) {
        JsonNode imgs = p.get("images");
        if (imgs != null && imgs.isArray() && imgs.size() > 0) return text(imgs.get(0), "src");
        return null;
    }

    /** Shopify {@code /products.json} (or any {products:[...]} / bare array). */
    List<ScrapedProduct> parseShopify(String json, Shop shop) {
        List<ScrapedProduct> out = new ArrayList<>();
        String root = shop == null ? null : trimSlash(shop.getBaseUrl());
        try {
            JsonNode tree = mapper.readTree(json);
            JsonNode products = tree.has("products") ? tree.get("products") : (tree.isArray() ? tree : null);
            if (products == null || !products.isArray()) return out;
            for (JsonNode p : products) {
                if (out.size() >= maxItems) break;
                String title = text(p, "title");
                if (title == null) continue;
                Double price = null;
                boolean inStock = true;
                JsonNode variants = p.get("variants");
                if (variants != null && variants.isArray() && variants.size() > 0) {
                    JsonNode v0 = variants.get(0);
                    price = num(text(v0, "price"));
                    if (v0.has("available")) inStock = v0.get("available").asBoolean(true);
                }
                if (price == null) continue;
                String handle = text(p, "handle");
                String url = (root != null && handle != null) ? root + "/products/" + handle : null;
                out.add(ScrapedProduct.builder()
                        .name(title).price(price).inStock(inStock)
                        .productUrl(url).imageUrl(shopifyImage(p))
                        .build());
            }
        } catch (Exception e) {
            log.warn("Feed sync: Shopify JSON parse failed — {}", e.getMessage());
        }
        return out;
    }

    /** Google-Merchant / RSS XML: one {@code <item>} per product, {@code g:}-prefixed fields. */
    List<ScrapedProduct> parseXml(String xml) {
        List<ScrapedProduct> out = new ArrayList<>();
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(false);   // keep g: prefixes as literal tag names
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);   // XXE guard
            Document doc = f.newDocumentBuilder()
                    .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            NodeList items = doc.getElementsByTagName("item");
            for (int i = 0; i < items.getLength() && out.size() < maxItems; i++) {
                Element it = (Element) items.item(i);
                String title = first(it, "title", "g:title");
                Double price = num(first(it, "g:price", "price", "g:sale_price"));
                if (title == null || price == null) continue;
                String avail = first(it, "g:availability", "availability");
                boolean inStock = avail == null || avail.toLowerCase().contains("in stock") || avail.equalsIgnoreCase("in_stock");
                out.add(ScrapedProduct.builder()
                        .name(title).price(price).inStock(inStock)
                        .productUrl(first(it, "link", "g:link"))
                        .imageUrl(first(it, "g:image_link", "image_link"))
                        .build());
            }
        } catch (Exception e) {
            log.warn("Feed sync: XML parse failed — {}", e.getMessage());
        }
        return out;
    }

    private static String shopifyImage(JsonNode p) {
        if (p.has("image") && p.get("image").has("src")) return p.get("image").get("src").asText(null);
        JsonNode imgs = p.get("images");
        if (imgs != null && imgs.isArray() && imgs.size() > 0) return text(imgs.get(0), "src");
        return null;
    }

    private static String text(JsonNode n, String field) {
        if (n == null || !n.has(field) || n.get(field).isNull()) return null;
        String s = n.get(field).asText(null);
        return s == null || s.isBlank() ? null : s.trim();
    }

    /** First non-empty child text among the given tag names (handles g:-prefixed). */
    private static String first(Element parent, String... tags) {
        for (String tag : tags) {
            NodeList nl = parent.getElementsByTagName(tag);
            if (nl.getLength() > 0) {
                String t = nl.item(0).getTextContent();
                if (t != null && !t.trim().isEmpty()) return t.trim();
            }
        }
        return null;
    }

    /** First numeric token as a double (strips currency text + thousands commas). */
    static Double num(String s) {
        if (s == null) return null;
        Matcher m = NUM.matcher(s);
        if (!m.find()) return null;
        try { return Double.parseDouble(m.group().replace(",", "")); }
        catch (NumberFormatException e) { return null; }
    }

    private static String trimSlash(String s) {
        if (s == null) return null;
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
