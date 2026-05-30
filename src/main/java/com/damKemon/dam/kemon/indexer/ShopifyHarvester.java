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

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * Universal harvester for Shopify shops. Every Shopify store exposes its full
 * catalog as JSON at {@code /products.json} (paginated, {@code ?limit=250&page=N})
 * — no per-shop code, no Playwright. One component cracks the whole Shopify
 * cluster (Aarong, Yellow, Sailor, Ecstasy, Cat's Eye, …).
 *
 * <p>Cloudflare-protected stores just return non-200 → empty list, and
 * {@link BulkIndexer} falls through to the normal pipeline (so this only ever
 * helps).
 */
@Service
public class ShopifyHarvester implements ShopHarvester {

    private static final Logger log = LoggerFactory.getLogger(ShopifyHarvester.class);
    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";

    @Value("${shopify.max-pages:6}")          private int maxPages;
    @Value("${shopify.page-size:250}")        private int pageSize;
    @Value("${shopify.max-products:500}")     private int maxProducts;
    @Value("${shopify.timeout-ms:15000}")     private int timeoutMs;
    @Value("${shopify.request-delay-ms:400}") private long requestDelayMs;

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public boolean supports(Shop shop) {
        return shop != null && "shopify".equalsIgnoreCase(shop.getPlatform()) && shop.getBaseUrl() != null;
    }

    @Override
    public List<ScrapedProduct> harvest(Shop shop) {
        String root = rootOf(shop.getBaseUrl());
        if (root == null) return List.of();
        List<ScrapedProduct> out = new ArrayList<>();
        for (int page = 1; page <= maxPages && out.size() < maxProducts; page++) {
            JsonNode products = call(root + "/products.json?limit=" + pageSize + "&page=" + page);
            if (products == null || !products.isArray() || products.isEmpty()) break;
            for (JsonNode p : products) {
                ScrapedProduct sp = map(p, root);
                if (sp != null) out.add(sp);
                if (out.size() >= maxProducts) break;
            }
        }
        if (!out.isEmpty()) {
            log.info("Shopify harvest: shop '{}' → {} products via /products.json", shop.getSlug(), out.size());
        }
        return out;
    }

    private JsonNode call(String url) {
        try {
            Connection.Response res = Jsoup.connect(url)
                    .userAgent(UA)
                    .header("Accept", "application/json")
                    .ignoreContentType(true)
                    .ignoreHttpErrors(true)
                    .timeout(timeoutMs)
                    .maxBodySize(0)
                    .method(Connection.Method.GET)
                    .execute();
            if (res.statusCode() != 200) return null;
            sleep(requestDelayMs);
            return mapper.readTree(res.body()).path("products");
        } catch (Exception e) {
            log.debug("Shopify call failed {}: {}", url, e.getMessage());
            return null;
        }
    }

    private ScrapedProduct map(JsonNode p, String root) {
        String title = text(p, "title");
        if (title == null) return null;
        JsonNode variants = p.path("variants");
        if (!variants.isArray() || variants.isEmpty()) return null;
        JsonNode v = variants.get(0);
        Double price = dbl(v, "price");
        if (price == null || price < 1) return null;
        Double compare = dbl(v, "compare_at_price");
        String handle = text(p, "handle");
        JsonNode images = p.path("images");
        String img = (images.isArray() && !images.isEmpty()) ? text(images.get(0), "src") : null;
        return ScrapedProduct.builder()
                .name(title)
                .price(price)
                .originalPrice(compare != null && compare > price ? compare : null)
                .imageUrl(img)
                .productUrl(handle != null ? root + "/products/" + handle : null)
                .inStock(v.path("available").asBoolean(true))
                .build();
    }

    private static String text(JsonNode n, String f) {
        JsonNode v = n.path(f);
        return v.isMissingNode() || v.isNull() || v.asText().isBlank() ? null : v.asText().trim();
    }

    private static Double dbl(JsonNode n, String f) {
        JsonNode v = n.path(f);
        if (v.isNumber()) return v.asDouble();
        if (v.isTextual()) {
            try { return Double.parseDouble(v.asText().trim()); } catch (NumberFormatException e) { return null; }
        }
        return null;
    }

    private static String rootOf(String url) {
        try { URI u = URI.create(url); return u.getScheme() + "://" + u.getHost(); }
        catch (Exception e) { return null; }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
