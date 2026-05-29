package com.damKemon.dam.kemon.indexer;

import com.damKemon.dam.kemon.model.Shop;
import com.damKemon.dam.kemon.scraper.BrowserFetcher;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Third-tier URL discovery for shops where neither sitemap nor homepage
 * crawl returns enough product URLs. Uses the shop's
 * {@link Shop#getSearchUrlTemplate()} with a small set of seed queries
 * derived from the shop's declared categories.
 *
 * <p>Example: a shop with categories {@code [smartphone, headphone]} and
 * search template {@code https://shop.com/?s={q}} gets fired with seeds
 * {@code [iphone, samsung, xiaomi, realme, oneplus, jbl, sony, bose,
 * airpods]}. Each search response page is harvested for anchors that
 * look like product URLs (same patterns as HomepageCrawler).
 *
 * <p>This is what catches Walton, Rangs, Singer, Esquire and similar
 * appliance retailers whose homepages list catalogues but not direct
 * product links, and whose sitemaps either 404 or omit product pages.
 */
@Service
public class SearchSeedCrawler {

    private static final Logger log = LoggerFactory.getLogger(SearchSeedCrawler.class);

    // Same product-URL heuristics HomepageCrawler uses, intentionally
    // duplicated here so the two crawlers can evolve independently.
    private static final Pattern PRODUCT_PATH = Pattern.compile(
            "/(product|products|p|item|sku|book|catalog/product)/[^/?#]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern PRODUCT_QS = Pattern.compile(
            "route=product/product", Pattern.CASE_INSENSITIVE);
    private static final Pattern LONG_ROOT_SLUG = Pattern.compile(
            "^/[a-z0-9][a-z0-9-]{19,}$", Pattern.CASE_INSENSITIVE);

    /**
     * Per-category seed phrases. Curated to be the brand/model names BD
     * shoppers actually type — running a search for "iphone" against any
     * major BD electronics retailer returns dozens of product cards.
     */
    private static final Map<String, List<String>> CATEGORY_SEEDS = new HashMap<>();
    static {
        CATEGORY_SEEDS.put("smartphone",   List.of("iphone", "samsung", "xiaomi", "realme", "oneplus", "vivo", "oppo", "infinix", "tecno", "walton"));
        CATEGORY_SEEDS.put("laptop",       List.of("laptop", "macbook", "thinkpad", "ideapad", "asus", "acer", "hp", "dell", "lenovo"));
        CATEGORY_SEEDS.put("desktop",      List.of("pc", "monitor", "graphics card", "ssd", "ram", "motherboard"));
        CATEGORY_SEEDS.put("tablet",       List.of("ipad", "galaxy tab", "tablet"));
        CATEGORY_SEEDS.put("headphone",    List.of("airpods", "jbl", "sony", "bose", "earbuds", "headphone", "tws"));
        CATEGORY_SEEDS.put("smartwatch",   List.of("apple watch", "galaxy watch", "mi band", "amazfit", "smartwatch"));
        CATEGORY_SEEDS.put("camera",       List.of("camera", "dslr", "mirrorless", "gopro", "lens"));
        CATEGORY_SEEDS.put("gaming",       List.of("ps5", "ps4", "xbox", "controller", "gaming chair"));
        CATEGORY_SEEDS.put("tv",           List.of("tv", "smart tv", "led tv", "oled"));
        CATEGORY_SEEDS.put("ac",           List.of("ac", "air conditioner", "split ac", "inverter ac"));
        CATEGORY_SEEDS.put("refrigerator", List.of("fridge", "refrigerator", "freezer"));
        CATEGORY_SEEDS.put("appliance",    List.of("microwave", "oven", "washing machine", "fan", "iron", "rice cooker", "blender"));
        CATEGORY_SEEDS.put("kitchen",      List.of("cookware", "dinner set", "kettle", "knife"));
        CATEGORY_SEEDS.put("fashion",      List.of("panjabi", "saree", "kurta", "shirt", "shoe", "sneaker"));
        CATEGORY_SEEDS.put("beauty",       List.of("lipstick", "foundation", "serum", "perfume", "cream"));
        CATEGORY_SEEDS.put("grocery",      List.of("rice", "oil", "shampoo", "soap", "diaper", "baby food", "detergent", "toothpaste", "sugar", "tea", "biscuit", "milk powder"));
        CATEGORY_SEEDS.put("food",         List.of("honey", "ghee", "mustard oil", "dates", "spices", "nuts", "flour", "rice"));
        CATEGORY_SEEDS.put("book",         List.of("humayun ahmed", "harry potter", "thriller", "novel", "biography"));
        CATEGORY_SEEDS.put("furniture",    List.of("sofa", "bed", "dining table", "wardrobe", "chair"));
        CATEGORY_SEEDS.put("home",         List.of("bedsheet", "curtain", "mat", "pillow"));
        CATEGORY_SEEDS.put("accessory",    List.of("charger", "cable", "power bank", "case"));
        // Everyday-goods buckets — without these, baby/toys/health/etc. fall back
        // to generic "popular/offer" seeds and never surface real products.
        CATEGORY_SEEDS.put("baby",         List.of("diaper", "baby food", "baby formula", "wet wipes", "feeding bottle", "baby lotion", "baby shampoo", "stroller"));
        CATEGORY_SEEDS.put("toys",         List.of("toy", "lego", "doll", "remote control car", "puzzle", "board game", "soft toy", "building blocks"));
        CATEGORY_SEEDS.put("pet",          List.of("dog food", "cat food", "cat litter", "pet shampoo", "fish food", "pet toy", "aquarium"));
        CATEGORY_SEEDS.put("health",       List.of("vitamin", "supplement", "hand sanitizer", "face mask", "thermometer", "sanitary napkin", "protein powder", "first aid"));
        CATEGORY_SEEDS.put("pharmacy",     List.of("napa", "paracetamol", "vitamin", "supplement", "antiseptic", "hand sanitizer", "savlon", "medicine"));
        CATEGORY_SEEDS.put("jewellery",    List.of("gold ring", "necklace", "earrings", "bangle", "bracelet", "gold chain", "pendant"));
        CATEGORY_SEEDS.put("sports",       List.of("football jersey", "argentina jersey", "brazil jersey", "world cup flag", "jersey", "cricket bat", "football", "dumbbell"));
        CATEGORY_SEEDS.put("stationery",   List.of("pen", "notebook", "diary", "marker", "file", "calculator", "sticky notes"));
        CATEGORY_SEEDS.put("office",       List.of("printer", "a4 paper", "stapler", "office chair", "calculator", "file"));
        CATEGORY_SEEDS.put("automotive",   List.of("helmet", "engine oil", "car cover", "car perfume", "tyre", "bike accessories"));
        CATEGORY_SEEDS.put("tools",        List.of("drill machine", "screwdriver", "wrench", "tool set", "hand tools", "soldering iron"));
        CATEGORY_SEEDS.put("power",        List.of("ips", "ups", "inverter", "battery", "generator", "voltage stabilizer"));
        CATEGORY_SEEDS.put("lighting",     List.of("led bulb", "tube light", "ceiling light", "chandelier", "light switch", "led panel"));
        CATEGORY_SEEDS.put("gadget",       List.of("power bank", "smart watch", "earbuds", "trimmer", "bluetooth speaker", "selfie stick"));
        CATEGORY_SEEDS.put("general",      List.of("popular", "best seller", "new arrival", "offer"));
    }
    private static final List<String> FALLBACK_SEEDS = List.of("popular", "best seller", "new arrival", "offer");

    @Value("${indexer.search-seed-timeout-ms:15000}")
    private int timeoutMs;

    @Value("${indexer.search-seed-max-seeds-per-shop:6}")
    private int maxSeedsPerShop;

    @Value("${indexer.search-seed-max-urls-per-shop:300}")
    private int maxUrlsPerShop;

    @Value("${indexer.user-agent:Mozilla/5.0 DamKemon/1.0}")
    private String userAgent;

    private final BrowserFetcher browser;

    public SearchSeedCrawler(BrowserFetcher browser) {
        this.browser = browser;
    }

    public List<String> crawl(Shop shop, boolean useJs) {
        if (shop.getSearchUrlTemplate() == null || shop.getSearchUrlTemplate().isBlank()) return List.of();
        String template = shop.getSearchUrlTemplate();
        if (!template.contains("{q}")) return List.of();

        List<String> seeds = pickSeeds(shop.getCategories());
        if (seeds.isEmpty()) return List.of();

        String baseHost = hostOf(shop.getBaseUrl());
        if (baseHost == null) return List.of();
        baseHost = stripWww(baseHost);

        LinkedHashSet<String> urls = new LinkedHashSet<>();
        for (String seed : seeds) {
            if (urls.size() >= maxUrlsPerShop) break;
            String url = template.replace("{q}", URLEncoder.encode(seed, StandardCharsets.UTF_8));
            try {
                Document doc = useJs ? browser.fetchDocument(url) : Jsoup.connect(url)
                        .userAgent(userAgent)
                        .timeout(timeoutMs)
                        .followRedirects(true)
                        .get();
                if (doc == null) continue;
                int added = 0;
                for (Element a : doc.select("a[href]")) {
                    String href = a.absUrl("href");
                    if (href.isBlank()) continue;
                    String linkHost = stripWww(hostOf(href));
                    if (linkHost == null || !linkHost.equals(baseHost)) continue;
                    if (!looksLikeProductUrl(href)) continue;
                    if (urls.add(href)) added++;
                    if (urls.size() >= maxUrlsPerShop) break;
                }
                log.debug("SearchSeed: shop '{}' seed '{}' → {} new product URLs", shop.getSlug(), seed, added);
            } catch (Exception e) {
                log.debug("SearchSeed: shop '{}' seed '{}' failed: {}", shop.getSlug(), seed, e.getMessage());
            }
        }

        if (!urls.isEmpty()) {
            log.info("SearchSeed: shop '{}' search-URL fallback yielded {} product URLs ({} seeds tried)",
                    shop.getSlug(), urls.size(), seeds.size());
        }
        return new ArrayList<>(urls);
    }

    private List<String> pickSeeds(List<String> categories) {
        if (categories == null || categories.isEmpty()) {
            return FALLBACK_SEEDS.subList(0, Math.min(maxSeedsPerShop, FALLBACK_SEEDS.size()));
        }
        LinkedHashSet<String> seeds = new LinkedHashSet<>();
        for (String cat : categories) {
            if (cat == null) continue;
            List<String> bucket = CATEGORY_SEEDS.getOrDefault(cat.toLowerCase(), FALLBACK_SEEDS);
            for (String s : bucket) {
                seeds.add(s);
                if (seeds.size() >= maxSeedsPerShop) return new ArrayList<>(seeds);
            }
        }
        return new ArrayList<>(seeds);
    }

    private static boolean looksLikeProductUrl(String url) {
        if (url == null) return false;
        try {
            URI u = URI.create(url);
            String path = u.getPath();
            String query = u.getQuery() == null ? "" : u.getQuery();
            if (path == null || path.isBlank() || "/".equals(path)) return false;
            if (PRODUCT_PATH.matcher(path).find()) return true;
            if (PRODUCT_QS.matcher(query).find()) return true;
            if (LONG_ROOT_SLUG.matcher(path).matches()) return true;
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private static String hostOf(String url) {
        if (url == null) return null;
        try { return URI.create(url).getHost(); }
        catch (Exception e) { return null; }
    }

    private static String stripWww(String h) {
        if (h == null) return null;
        return h.toLowerCase().startsWith("www.") ? h.substring(4) : h.toLowerCase();
    }
}
