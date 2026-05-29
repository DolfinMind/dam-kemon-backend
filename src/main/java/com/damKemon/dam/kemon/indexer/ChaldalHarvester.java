package com.damKemon.dam.kemon.indexer;

import com.damKemon.dam.kemon.model.Shop;
import com.damKemon.dam.kemon.scraper.ScrapedProduct;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * API-based harvester for Chaldal — Bangladesh's largest online grocery, and
 * our single biggest source of everyday goods (shampoo, soap, oil, baby food,
 * diapers, OTC health). Chaldal is a React SPA with no crawlable product
 * anchors and no sitemap, so the usual sitemap/homepage/search-seed discovery
 * returns nothing and the generic schema.org extractor has no HTML to read.
 *
 * <p>Instead we call Chaldal's own catalog endpoint
 * ({@code catalog.chaldal.com/searchPersonalized}) — the same JSON API the
 * site's frontend uses. It returns clean product records (name, price, mrp,
 * slug, image URLs), which we map straight to {@link ScrapedProduct}. This is
 * far more robust than DOM scraping a client-rendered page.
 *
 * <p>Discovery is done by firing a curated set of everyday-goods queries and
 * de-duplicating by Chaldal's {@code objectID}. {@link BulkIndexer} routes the
 * "chaldal" shop here (see {@code supports(Shop)}) and persists the result
 * through its normal cross-shop merge path; every other shop is untouched.
 */
@Service
public class ChaldalHarvester {

    private static final Logger log = LoggerFactory.getLogger(ChaldalHarvester.class);

    private static final String API = "https://catalog.chaldal.com/searchPersonalized";
    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";

    /**
     * Public client key the Chaldal web app ships in its bundle. Overridable
     * via {@code CHALDAL_API_KEY} in case it rotates — if a run suddenly
     * harvests 0 products, this is the first thing to check.
     */
    @Value("${chaldal.api-key:e964fc2d51064efa97e94db7c64bf3d044279d4ed0ad4bdd9dce89fecc9156f0}")
    private String apiKey;

    @Value("${chaldal.store-id:1}")          private int storeId;
    @Value("${chaldal.warehouse-id:8}")      private int warehouseId;
    @Value("${chaldal.metro-area-id:1}")     private int metroAreaId;
    @Value("${chaldal.page-size:48}")        private int pageSize;
    @Value("${chaldal.max-pages-per-query:2}") private int maxPagesPerQuery;
    @Value("${chaldal.max-products:600}")    private int maxProducts;
    /**
     * Cap NEW products taken per seed query. Keeps the harvest balanced across
     * categories — without it the first few high-volume queries (shampoo, rice,
     * ...) eat the whole budget and the later baby/health/cleaning queries never
     * contribute. ~16 × 32 queries ≈ the 500 per-shop persist cap.
     */
    @Value("${chaldal.max-per-query:16}")    private int maxPerQuery;
    @Value("${chaldal.timeout-ms:15000}")    private int timeoutMs;
    @Value("${chaldal.request-delay-ms:350}") private long requestDelayMs;

    /**
     * Seed queries spanning the everyday categories we want from Chaldal.
     * Breadth matters more than depth here — each query returns a different
     * slice and we de-dupe across them by objectID.
     */
    private static final List<String> QUERIES = List.of(
            "shampoo", "soap", "body wash", "hand wash", "toothpaste", "lotion", "face wash",
            "oil", "rice", "flour", "sugar", "salt", "spices", "lentil", "tea", "coffee",
            "milk", "biscuit", "snacks", "noodles", "juice", "sauce",
            "diaper", "baby food", "baby", "wet wipes",
            "sanitary napkin", "tissue", "detergent", "dishwashing", "cleaner", "shaving"
    );

    private final ObjectMapper mapper = new ObjectMapper();

    /** True for the Chaldal shop only — everything else uses the normal pipeline. */
    public boolean supports(Shop shop) {
        return shop != null && "chaldal".equalsIgnoreCase(shop.getSlug());
    }

    /** Fire every seed query, page through results, de-dupe by objectID. */
    public List<ScrapedProduct> harvest() {
        LinkedHashMap<Long, ScrapedProduct> byId = new LinkedHashMap<>();
        for (String q : QUERIES) {
            if (byId.size() >= maxProducts) break;
            int fromThisQuery = 0;
            for (int page = 0; page < maxPagesPerQuery && fromThisQuery < maxPerQuery; page++) {
                if (byId.size() >= maxProducts) break;
                JsonNode root = call(q, page);
                if (root == null) break;
                JsonNode hits = root.path("hits");
                if (!hits.isArray() || hits.isEmpty()) break;
                for (JsonNode h : hits) {
                    if (fromThisQuery >= maxPerQuery) break;
                    long id = h.path("objectID").asLong(-1);
                    if (id < 0 || byId.containsKey(id)) continue;
                    ScrapedProduct sp = map(h);
                    if (sp != null) { byId.put(id, sp); fromThisQuery++; }
                }
                if (page + 1 >= root.path("nbPages").asInt(0)) break;
            }
        }
        log.info("Chaldal harvest: {} distinct products from {} seed queries", byId.size(), QUERIES.size());
        return new ArrayList<>(byId.values());
    }

    private JsonNode call(String query, int page) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("apiKey", apiKey);
            body.put("storeId", storeId);
            body.put("warehouseId", warehouseId);
            body.put("pageSize", pageSize);
            body.put("currentPageIndex", page);
            body.put("metropolitanAreaId", metroAreaId);
            body.put("query", query);
            body.put("productVariantId", -1);
            body.put("canSeeOutOfStock", "true");
            body.put("filters", List.of());

            Connection.Response res = Jsoup.connect(API)
                    .userAgent(UA)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("Origin", "https://chaldal.com")
                    .header("Referer", "https://chaldal.com/")
                    .requestBody(mapper.writeValueAsString(body))
                    .ignoreContentType(true)
                    .ignoreHttpErrors(true)
                    .timeout(timeoutMs)
                    .maxBodySize(0)
                    .method(Connection.Method.POST)
                    .execute();

            if (res.statusCode() != 200) {
                log.debug("Chaldal API {} for q='{}' p={}", res.statusCode(), query, page);
                return null;
            }
            sleep(requestDelayMs);
            return mapper.readTree(res.body());
        } catch (Exception e) {
            log.debug("Chaldal API call failed q='{}' p={}: {}", query, page, e.getMessage());
            return null;
        }
    }

    /** Map one catalog hit to a ScrapedProduct, or null if it's unusable. */
    private ScrapedProduct map(JsonNode h) {
        String name = firstNonBlank(text(h, "name"), text(h, "nameWithoutSubText"));
        if (name == null) return null;
        Double price = firstPositive(dbl(h, "price"), dbl(h, "corpPrice"));
        if (price == null) return null;

        String sub = text(h, "subText");
        String fullName = (sub != null && !sub.isBlank() && !name.contains(sub))
                ? (name + " " + sub).trim() : name.trim();

        Double mrp = dbl(h, "mrp");
        String slug = text(h, "slug");
        String url = slug != null && !slug.isBlank()
                ? "https://chaldal.com/" + slug
                : "https://chaldal.com/?p=" + h.path("objectID").asLong(0);

        return ScrapedProduct.builder()
                .name(fullName)
                .price(price)
                .originalPrice(mrp != null && mrp > price ? mrp : null)
                .imageUrl(firstPicture(h))
                .productUrl(url)
                .inStock(true)
                .build();
    }

    private static String firstPicture(JsonNode h) {
        JsonNode pics = h.path("picturesUrls");
        if (pics.isArray()) {
            for (JsonNode p : pics) {
                if (p.isTextual() && !p.asText().isBlank()) return p.asText();
                String u = firstNonBlank(textOf(p, "url"), textOf(p, "src"));
                if (u != null) return u;
            }
        }
        return null;
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return v.isMissingNode() || v.isNull() || v.asText().isBlank() ? null : v.asText().trim();
    }

    private static String textOf(JsonNode n, String field) {
        return n.isObject() ? text(n, field) : null;
    }

    private static Double dbl(JsonNode n, String field) {
        JsonNode v = n.path(field);
        if (v.isNumber()) return v.asDouble();
        if (v.isTextual()) {
            try { return Double.parseDouble(v.asText().trim()); } catch (NumberFormatException ignored) { return null; }
        }
        return null;
    }

    private static String firstNonBlank(String... vals) {
        for (String v : vals) if (v != null && !v.isBlank()) return v;
        return null;
    }

    private static Double firstPositive(Double... vals) {
        for (Double v : vals) if (v != null && v > 0) return v;
        return null;
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
