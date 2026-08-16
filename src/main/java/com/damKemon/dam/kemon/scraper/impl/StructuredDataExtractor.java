package com.damKemon.dam.kemon.scraper.impl;

import com.damKemon.dam.kemon.intelligence.PriceParser;
import com.damKemon.dam.kemon.scraper.BaseScraper;
import com.damKemon.dam.kemon.scraper.ProductExtractor;
import com.damKemon.dam.kemon.scraper.ScrapedProduct;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Extracts a product from the JSON that modern frameworks embed in the page —
 * Next.js {@code <script id="__NEXT_DATA__" type="application/json">}, and any
 * other {@code application/json} blocks (Nuxt payloads, hydration state, etc.).
 *
 * <p>The {@code GenericProductExtractor} already covers JSON-LD + OpenGraph;
 * this fills the gap for server-rendered SPAs that hide their product in a JS
 * state blob with no schema.org markup. {@link #supports(String)} returns false
 * so it isn't auto-routed by URL — instead the {@code ScraperLearningService}
 * tries it on broken shops and pins it as {@code preferredExtractor} where it
 * wins, so the engine adopts it automatically per shop.
 */
@Component
public class StructuredDataExtractor extends BaseScraper implements ProductExtractor {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String[] NAME_KEYS  = {"name", "title", "productname", "product_name", "displayname"};
    private static final String[] PRICE_KEYS = {"price", "saleprice", "sale_price", "specialprice", "currentprice", "finalprice", "unitprice", "offerprice", "amount"};
    private static final String[] IMG_KEYS   = {"image", "imageurl", "image_url", "img", "thumbnail", "featured_image", "picture"};

    @Override public String getSiteName() { return "Embedded Data"; }
    @Override public String getSiteSlug() { return "embedded-data"; }

    /** Not URL-routable — adopted per shop by the learner when it works. */
    @Override public boolean supports(String url) { return false; }

    @Override
    public ScrapedProduct extract(String url) {
        Document doc;
        try { doc = fetch(url); } catch (Exception e) { return null; }
        if (doc == null) return null;
        for (Element s : doc.select("script[type=application/json]")) {
            String json = s.html();
            if (json == null || json.length() < 30) continue;
            ScrapedProduct sp = firstProduct(json);
            if (sp != null) { sp.setProductUrl(url); return sp; }
        }
        return null;
    }

    /** Walk the JSON; return the first product-shaped object (name + price). */
    private ScrapedProduct firstProduct(String json) {
        JsonNode root;
        try { root = MAPPER.readTree(json); } catch (Exception e) { return null; }
        Deque<JsonNode> stack = new ArrayDeque<>();
        stack.push(root);
        int visited = 0;
        while (!stack.isEmpty() && visited < 50_000) {
            JsonNode n = stack.pop();
            visited++;
            if (n.isObject()) {
                ScrapedProduct sp = asProduct(n);
                if (sp != null) return sp;
                for (JsonNode c : n) if (c.isContainerNode()) stack.push(c);
            } else if (n.isArray()) {
                for (JsonNode c : n) if (c.isContainerNode()) stack.push(c);
            }
        }
        return null;
    }

    private ScrapedProduct asProduct(JsonNode o) {
        String name = firstString(o, NAME_KEYS);
        if (name == null || name.length() < 3 || name.length() > 300) return null;
        Double price = firstPrice(o);
        if (price == null || price < 1 || price > 10_000_000) return null;
        return ScrapedProduct.builder()
                .name(name).price(price)
                .imageUrl(firstString(o, IMG_KEYS))
                .inStock(true).build();
    }

    private static String firstString(JsonNode o, String[] keys) {
        for (String k : keys) {
            JsonNode v = getCi(o, k);
            if (v != null && v.isTextual() && !v.asText().isBlank()) return v.asText().trim();
        }
        return null;
    }

    private static Double firstPrice(JsonNode o) {
        for (String k : PRICE_KEYS) {
            JsonNode v = getCi(o, k);
            if (v == null) continue;
            if (v.isNumber()) return v.asDouble();
            if (v.isTextual()) { Double p = PriceParser.parseFirst(v.asText()); if (p != null) return p; }
        }
        return null;
    }

    private static JsonNode getCi(JsonNode o, String key) {
        JsonNode direct = o.get(key);
        if (direct != null) return direct;
        var it = o.fieldNames();
        while (it.hasNext()) { String f = it.next(); if (f.equalsIgnoreCase(key)) return o.get(f); }
        return null;
    }
}
