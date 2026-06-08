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
            // ===== TECH DEPTH (harvested FIRST) — SPECIFIC models so many distinct
            // sellers converge on the SAME product, lifting sellers-per-product, not
            // just raw catalog breadth. Daraz rate-limits ~500 products/run from one
            // IP, so spend that budget on tech where the seller overlap is highest. =====
            "iphone 15", "iphone 15 plus", "iphone 15 pro", "iphone 15 pro max",
            "iphone 14", "iphone 14 pro max", "iphone 13", "iphone 12", "iphone 11",
            "iphone 16", "iphone 16 pro max", "iphone se",
            "samsung galaxy a05", "samsung galaxy a15", "samsung galaxy a25", "samsung galaxy a35",
            "samsung galaxy a55", "samsung galaxy s24", "samsung galaxy s24 ultra", "samsung galaxy s23",
            "samsung galaxy m15", "samsung galaxy f15",
            "redmi note 13", "redmi note 13 pro", "redmi note 12", "redmi 13c", "redmi a3",
            "poco x6", "poco x6 pro", "poco c65", "poco m6",
            "realme c55", "realme c53", "realme c63", "realme 12", "realme 12 pro", "realme narzo 70",
            "infinix hot 40", "infinix hot 40 pro", "infinix note 40", "infinix smart 8", "infinix zero 30",
            "tecno spark 20", "tecno spark 20 pro", "tecno camon 30", "tecno pop 8",
            "oppo a78", "oppo a58", "oppo a18", "oppo reno 11", "oppo reno 12",
            "vivo y28", "vivo y17s", "vivo y27", "vivo v30", "vivo v29",
            "oneplus nord ce 4", "oneplus 12r", "oneplus nord n30",
            "xiaomi 14", "xiaomi 13t", "motorola g54", "motorola g34",
            "nokia c32", "nokia g42", "honor x9b", "honor x8b", "honor 90",
            "walton primo", "symphony mobile", "itel a70", "itel p55",
            // — laptops (model-specific) —
            "asus vivobook", "asus tuf gaming laptop", "asus zenbook", "acer aspire", "acer nitro 5",
            "hp pavilion", "hp victus", "hp probook", "dell inspiron", "dell xps",
            "lenovo ideapad", "lenovo legion", "lenovo thinkpad", "msi gaming laptop", "msi modern",
            "macbook air m1", "macbook air m2", "macbook air m3", "macbook pro m3",
            // — tablets —
            "samsung galaxy tab a9", "samsung galaxy tab s9", "ipad 9th gen", "ipad 10th gen",
            "ipad air", "xiaomi pad 6", "lenovo tab m10", "realme pad",
            // — tech accessories (very high seller convergence per SKU) —
            "type c cable", "lightning cable", "65w charger", "33w charger", "20w charger",
            "power bank 20000mah", "power bank 10000mah", "tempered glass", "magsafe charger",
            "anker power bank", "baseus charger", "ugreen cable", "joyroom power bank",
            "airpods pro 2", "airpods 3", "tws earbuds", "bluetooth neckband", "wireless earbuds",
            "samsung buds", "soundcore earbuds", "jbl earbuds", "havit earbuds",
            "amazfit watch", "smart watch", "apple watch series 9", "redmi watch",
            // — components & PC —
            "ssd 256gb", "ssd 512gb", "ssd 1tb", "ram 8gb ddr4", "ram 16gb",
            "wifi router", "pendrive 32gb", "pendrive 64gb", "memory card 64gb", "memory card 128gb",
            "gaming mouse", "mechanical keyboard", "rtx 4060", "monitor 24 inch", "webcam",
            // ===== END TECH DEPTH =====
            // Distinct seed queries fan out across Daraz's catalog; more diverse
            // seeds = more DISTINCT products (each ~20-40). Cover the high-inventory
            // categories so the marketplace contributes volume, not just WC merch.
            // — phones & mobile —
            "smartphone", "android phone", "samsung phone", "xiaomi phone", "realme phone",
            "vivo phone", "oppo phone", "infinix phone", "tecno phone", "feature phone",
            "tablet", "phone case", "screen protector", "phone charger", "usb cable",
            // — computing —
            "laptop", "gaming laptop", "desktop computer", "monitor", "mechanical keyboard",
            "mouse", "ssd", "pen drive", "memory card", "wifi router", "printer", "webcam",
            // — audio & wearables —
            "headphone", "earphone", "earbuds", "bluetooth speaker", "neckband",
            "smart watch", "fitness band", "power bank",
            // — home appliances —
            "rice cooker", "blender", "electric kettle", "iron", "ceiling fan", "air cooler",
            "microwave oven", "refrigerator", "washing machine", "water filter", "gas stove",
            "induction cooker", "vacuum cleaner", "sewing machine", "hair dryer", "trimmer",
            // — beauty & personal care —
            "lipstick", "foundation", "face wash", "moisturizer", "sunscreen", "face serum",
            "shampoo", "conditioner", "hair oil", "perfume", "body spray", "makeup kit",
            // — fashion —
            "t shirt", "casual shirt", "jeans pant", "panjabi", "saree", "kurti",
            "salwar kameez", "sneaker", "sandal", "formal shoe", "hand bag", "backpack",
            "wallet", "leather belt", "sunglasses", "wrist watch",
            // — baby & kids —
            "baby diaper", "baby food", "feeding bottle", "kids toys", "remote control car",
            "doll", "school bag",
            // — home & kitchen —
            "bedsheet", "blanket", "pillow", "curtain", "wall clock", "cookware set",
            "dinner set", "water bottle", "lunch box", "storage box", "led light",
            // — sports & outdoors —
            "cricket bat", "football", "yoga mat", "dumbbell", "bicycle", "helmet",
            // — tools & stationery —
            "drill machine", "screwdriver set", "notebook diary", "ball pen",
            // — seasonal fan merch (kept) —
            "argentina jersey", "brazil jersey", "portugal jersey", "football jersey", "national flag",
            // — BRAND seeds: on Daraz each brand is stocked by MANY storefronts, so a
            //   brand query surfaces lots of DISTINCT sellers (the goal for breadth) —
            "anker", "baseus", "ugreen", "remax", "joyroom", "havit", "hoco", "awei", "ldnio", "wiwu",
            "jbl", "soundcore", "boat", "edifier", "f&d", "oraimo", "realme buds", "xiaomi", "samsung",
            "apple", "realme", "oppo", "vivo", "infinix", "tecno", "motorola", "nokia", "honor", "oneplus",
            "logitech", "a4tech", "rapoo", "fantech", "redragon", "asus", "msi", "gigabyte", "kingston", "wd",
            "amazfit", "haylou", "mibro", "colmi", "fire boltt", "noise", "walton", "vision", "singer", "miyako",
            "conion", "philips", "panasonic", "lg", "haier", "transtec", "sony", "jvc", "tcl", "hisense",
            "lakme", "loreal", "nivea", "garnier", "ponds", "himalaya", "dove", "sunsilk", "nature republic", "the body shop",
            "skin cafe", "cetaphil", "neutrogena", "maybelline", "sheglam", "color studio", "wet n wild", "minimalist", "cosrx", "the ordinary",
            // — niche categories: long-tail sellers —
            "mobile accessories", "smart home", "kitchen appliance", "home decor", "gift item", "stationery",
            "school bag", "office chair", "study table", "wall shelf", "led tv", "sound bar", "home theatre",
            "gas burner", "pressure cooker", "non stick pan", "water bottle", "lunch box", "thermos flask",
            "bed sheet", "blanket", "towel", "curtain", "door mat", "wall clock", "photo frame",
            "three piece", "kurti", "sharee", "borka", "hijab", "tshirt men", "polo shirt", "denim jeans",
            "kids dress", "baby toys", "feeding bottle", "baby diaper", "stroller", "walker",
            "perfume", "attar", "body spray", "deodorant", "face wash", "hair oil", "shampoo", "lipstick",
            "sunglass", "wrist watch men", "leather wallet", "ladies bag", "backpack", "trolley bag",
            "power bank", "usb cable", "wall charger", "earphone", "neckband", "tws earbuds", "smart watch",
            "trimmer", "shaver", "hair dryer", "hair straightener", "electric kettle", "blender", "rice cooker",
            "drill machine", "hand tools", "measuring tape", "cricket bat ball", "badminton racket", "yoga mat set",
            "car accessories", "bike accessories", "helmet", "fishing", "camping", "gardening tools"
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

        // Prefer Daraz's own canonical item URL (it's protocol-relative) so each
        // seller's listing has its true, clickable URL; reconstruct only if absent.
        String url = absUrl(text(it, "itemUrl"));
        if (url == null) {
            String skuId = text(it, "skuId");
            String slug = slugify(name);
            url = "https://www.daraz.com.bd/products/" + (slug.isBlank() ? "p" : slug)
                    + "-i" + itemId + (skuId != null && !skuId.isBlank() ? "-s" + skuId : "") + ".html";
        }

        JsonNode inStock = it.path("inStock");
        return ScrapedProduct.builder()
                .name(name)
                .price(price)
                .originalPrice(original != null && original > price ? original : null)
                .imageUrl(absUrl(text(it, "image")))
                .productUrl(url)
                .inStock(!inStock.isBoolean() || inStock.asBoolean())
                // Per-listing seller + quality signals — the whole point of treating
                // each Daraz listing as a distinct seller offer.
                .rating(parseDouble(text(it, "ratingScore")))
                .reviewCount(parseCount(text(it, "review")))
                .soldCount(parseSold(text(it, "itemSoldCntShow")))
                .sellerName(clean(text(it, "sellerName")))
                .sellerId(text(it, "sellerId"))
                .build();
    }

    /** Normalise Daraz's protocol-relative ("//...") and relative URLs to absolute https. */
    private static String absUrl(String u) {
        if (u == null || u.isBlank()) return null;
        if (u.startsWith("//")) return "https:" + u;
        if (u.startsWith("http")) return u;
        if (u.startsWith("/")) return "https://www.daraz.com.bd" + u;
        return u;
    }

    private static Double parseDouble(String s) {
        if (s == null) return null;
        try {
            double d = Double.parseDouble(s.replaceAll("[^0-9.]", ""));
            return (d > 0 && d <= 5) ? Math.round(d * 10.0) / 10.0 : null;
        } catch (NumberFormatException e) { return null; }
    }

    /** Plain integer count, e.g. review count "1234" or "(1234)". */
    private static Integer parseCount(String s) {
        if (s == null) return null;
        String digits = s.replaceAll("[^0-9]", "");
        if (digits.isBlank()) return null;
        try { return Integer.parseInt(digits); } catch (NumberFormatException e) { return null; }
    }

    /** Units sold, e.g. "1.2K sold" → 1200, "350 sold" → 350. */
    private static Integer parseSold(String s) {
        if (s == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("([0-9]+(?:\\.[0-9]+)?)\\s*([kKmM]?)").matcher(s);
        if (!m.find()) return null;
        try {
            double n = Double.parseDouble(m.group(1));
            String suf = m.group(2).toLowerCase();
            if ("k".equals(suf)) n *= 1_000;
            else if ("m".equals(suf)) n *= 1_000_000;
            return (int) Math.round(n);
        } catch (NumberFormatException e) { return null; }
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
