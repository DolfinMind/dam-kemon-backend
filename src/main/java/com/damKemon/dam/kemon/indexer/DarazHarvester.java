package com.damKemon.dam.kemon.indexer;

import com.damKemon.dam.kemon.intelligence.PriceParser;
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

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * API harvester for Daraz — Bangladesh's dominant marketplace and the single
 * biggest source of the everyday/seasonal goods the rest of the catalog lacks
 * (team jerseys, flags, fan gear, baby, toys, fashion, home).
 *
 * <p>Daraz is a client-rendered SPA, but its catalog/search endpoint returns
 * plain JSON — {@code /catalog/?ajax=true&q=...&page=N} → {@code mods.listItems[]}
 * — so no Playwright is needed. We fire a curated set of gap-filling + World
 * Cup queries, de-dupe by Daraz {@code itemId}, and map each card to a
 * {@link ScrapedProduct}. {@link BulkIndexer} routes the "daraz" shop here and
 * runs it as part of the nightly indexer, so the catalog grows every day.
 */
@Service
public class DarazHarvester implements ShopHarvester {

    private static final Logger log = LoggerFactory.getLogger(DarazHarvester.class);

    private static final String SEARCH = "https://www.daraz.com.bd/catalog/?ajax=true&q=%s&page=%d";
    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";

    @Value("${daraz.max-pages-per-query:1}") private int maxPagesPerQuery;
    @Value("${daraz.max-per-query:24}")      private int maxPerQuery;
    @Value("${daraz.max-products:500}")      private int maxProducts;
    @Value("${daraz.timeout-ms:18000}")      private int timeoutMs;
    // Daraz rate-limits bursts; pace requests ~1.5s apart so the nightly harvest
    // isn't blocked (a single request is fine, ~60 rapid ones get throttled).
    @Value("${daraz.request-delay-ms:1500}") private long requestDelayMs;

    /**
     * Gap-filling + seasonal queries. Daraz's electronics are already well
     * covered by the dedicated tech retailers, so we deliberately skip
     * phones/laptops and spend the budget on what's missing.
     */
    private static final List<String> QUERIES = List.of(
            "argentina jersey", "brazil jersey", "portugal jersey", "football jersey",
            "world cup flag", "national flag", "supporter jersey", "messi jersey", "ronaldo jersey",
            "baby diaper", "baby food", "kids toys", "remote control car", "doll",
            "shampoo", "body spray", "perfume", "saree", "panjabi", "kurti",
            "t shirt", "sneaker", "sandal", "bedsheet", "wall clock", "cookware",
            "cricket bat", "football", "yoga mat", "power bank", "smart watch", "trimmer"
    );

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public boolean supports(Shop shop) {
        return shop != null && "daraz".equalsIgnoreCase(shop.getSlug());
    }

    @Override
    public List<ScrapedProduct> harvest(Shop shop) {
        LinkedHashMap<String, ScrapedProduct> byId = new LinkedHashMap<>();
        for (String q : QUERIES) {
            if (byId.size() >= maxProducts) break;
            int fromThisQuery = 0;
            for (int page = 1; page <= maxPagesPerQuery && fromThisQuery < maxPerQuery; page++) {
                if (byId.size() >= maxProducts) break;
                JsonNode items = call(q, page);
                if (items == null || !items.isArray() || items.isEmpty()) break;
                for (JsonNode it : items) {
                    if (fromThisQuery >= maxPerQuery) break;
                    String itemId = text(it, "itemId");
                    if (itemId == null || byId.containsKey(itemId)) continue;
                    ScrapedProduct sp = map(it, itemId);
                    if (sp != null) { byId.put(itemId, sp); fromThisQuery++; }
                }
            }
        }
        log.info("Daraz harvest: {} distinct products from {} seed queries", byId.size(), QUERIES.size());
        return new ArrayList<>(byId.values());
    }

    private JsonNode call(String query, int page) {
        try {
            String url = String.format(SEARCH, URLEncoder.encode(query, StandardCharsets.UTF_8), page);
            Connection.Response res = Jsoup.connect(url)
                    .userAgent(UA)
                    .header("Accept", "application/json, text/plain, */*")
                    .header("X-Requested-With", "XMLHttpRequest")
                    .header("Referer", "https://www.daraz.com.bd/")
                    .ignoreContentType(true)
                    .ignoreHttpErrors(true)
                    .timeout(timeoutMs)
                    .maxBodySize(0)
                    .method(Connection.Method.GET)
                    .execute();
            if (res.statusCode() != 200) {
                log.debug("Daraz API {} for q='{}' p={}", res.statusCode(), query, page);
                return null;
            }
            sleep(requestDelayMs);
            return mapper.readTree(res.body()).path("mods").path("listItems");
        } catch (Exception e) {
            log.debug("Daraz API call failed q='{}' p={}: {}", query, page, e.getMessage());
            return null;
        }
    }

    private ScrapedProduct map(JsonNode it, String itemId) {
        String name = clean(text(it, "name"));
        if (name == null) return null;
        Double price = PriceParser.parseFirst(firstNonBlank(text(it, "priceShow"), text(it, "price")));
        if (price == null || price < 1) return null;
        Double original = PriceParser.parseFirst(text(it, "originalPriceShow"));
        String skuId = text(it, "skuId");
        String slug = slugify(name);
        String url = "https://www.daraz.com.bd/products/" + (slug.isBlank() ? "p" : slug)
                + "-i" + itemId + (skuId != null && !skuId.isBlank() ? "-s" + skuId : "") + ".html";
        JsonNode inStock = it.path("inStock");
        return ScrapedProduct.builder()
                .name(name)
                .price(price)
                .originalPrice(original != null && original > price ? original : null)
                .imageUrl(text(it, "image"))
                .productUrl(url)
                .inStock(!inStock.isBoolean() || inStock.asBoolean())
                .build();
    }

    /** Strip emoji/symbol junk Daraz sellers cram into titles ("... 🔥 300 Taka"). */
    private static String clean(String s) {
        if (s == null) return null;
        String out = s.replaceAll("[\\p{So}\\p{Cn}]", " ").replaceAll("\\s+", " ").trim();
        return out.isBlank() ? null : out;
    }

    private static String text(JsonNode n, String f) {
        JsonNode v = n.path(f);
        return v.isMissingNode() || v.isNull() || v.asText().isBlank() ? null : v.asText().trim();
    }

    private static String firstNonBlank(String... vals) {
        for (String v : vals) if (v != null && !v.isBlank()) return v;
        return null;
    }

    private static String slugify(String name) {
        return name.toLowerCase().replaceAll("[^a-z0-9\\s-]", "").replaceAll("\\s+", "-")
                .replaceAll("-+", "-").replaceAll("^-|-$", "");
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
