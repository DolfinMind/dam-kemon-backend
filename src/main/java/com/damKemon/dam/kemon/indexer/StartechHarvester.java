package com.damKemon.dam.kemon.indexer;

import com.damKemon.dam.kemon.intelligence.PriceParser;
import com.damKemon.dam.kemon.model.Shop;
import com.damKemon.dam.kemon.scraper.ScrapedProduct;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Category-pagination harvester for OpenCart-style flat-URL retailers whose
 * sitemap lists only CATEGORY pages — so the normal sitemap→extract path treats
 * categories as products and yields almost nothing — and whose product pages
 * carry NO JSON-LD (the price sits in an OpenCart {@code .price-new} element).
 * StarTech is the canonical case: ~85 products via the generic pipeline versus
 * the several thousand actually on the site.
 *
 * <p>Strategy, fully self-contained so it bypasses both the sitemap pre-emption
 * and the JSON-LD requirement:
 * <ol>
 *   <li>read the category sitemap → category URLs;</li>
 *   <li>walk each category with {@code ?page=N} pagination, collecting the
 *       long descriptive root-slug product URLs until a page adds nothing;</li>
 *   <li>fetch each product page and read name + {@code .price-new}/{@code
 *       .price-old} + {@code og:image}.</li>
 * </ol>
 *
 * <p>Ordered HIGHEST so it claims StarTech before the catch-all
 * {@link JsonCatalogHarvester} (which now probes every shop).
 */
@Service
@Order(Ordered.HIGHEST_PRECEDENCE)
public class StartechHarvester implements ShopHarvester {

    private static final Logger log = LoggerFactory.getLogger(StartechHarvester.class);
    private static final String UA =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";

    /** Shops with this exact OpenCart layout (category sitemap + .price-new). */
    private static final Set<String> SLUGS = Set.of("startech");

    @Value("${startech.max-categories:60}")    private int maxCategories;
    @Value("${startech.max-pages-per-cat:12}")  private int maxPagesPerCat;
    @Value("${startech.max-products:6000}")     private int maxProducts;
    @Value("${startech.timeout-ms:15000}")      private int timeoutMs;
    @Value("${startech.fetch-threads:12}")      private int fetchThreads;

    @Override
    public boolean supports(Shop shop) {
        return shop != null && shop.getSlug() != null && SLUGS.contains(shop.getSlug().toLowerCase());
    }

    @Override
    public List<ScrapedProduct> harvest(Shop shop) {
        String base = stripSlash(shop.getBaseUrl() == null || shop.getBaseUrl().isBlank()
                ? "https://www.startech.com.bd" : shop.getBaseUrl());

        List<String> locs = locsFromSitemap(base + "/sitemap.xml", base);

        // StarTech's sitemap.xml lists the WHOLE catalog (~25k entries): the long
        // descriptive root-slugs ARE products, the short/nested ones are
        // categories. Take the products directly — that's thousands of URLs, vs
        // the few hundred the old "walk 60 categories" path scraped.
        LinkedHashSet<String> productUrls = new LinkedHashSet<>();
        List<String> categories = new ArrayList<>();
        for (String u : locs) {
            if (isProductUrl(u, base)) {
                if (productUrls.size() < maxProducts) productUrls.add(u);
            } else if (!u.equals(base)) {
                categories.add(u);
            }
        }
        log.info("StarTech: sitemap gave {} product URLs directly + {} category pages",
                productUrls.size(), categories.size());

        // Fallback: if the sitemap was category-only (layout changed), walk
        // categories with pagination as before so we never regress to ~0.
        if (productUrls.size() < 200 && !categories.isEmpty()) {
            int cats = 0;
            for (String cat : categories) {
                if (cats >= maxCategories || productUrls.size() >= maxProducts) break;
                cats++;
                for (int page = 1; page <= maxPagesPerCat && productUrls.size() < maxProducts; page++) {
                    Document d = get(cat + (cat.contains("?") ? "&" : "?") + "page=" + page);
                    if (d == null) break;
                    int before = productUrls.size();
                    for (Element a : d.select("a[href]")) {
                        String href = stripQuery(a.absUrl("href"));
                        if (isProductUrl(href, base)) productUrls.add(href);
                    }
                    if (productUrls.size() == before) break;
                }
            }
            log.info("StarTech: category-pagination fallback brought total to {} product URLs", productUrls.size());
        }
        if (productUrls.isEmpty()) { log.warn("StarTech: no product URLs from sitemap"); return List.of(); }

        // Fetch + extract each product with bounded parallelism.
        List<ScrapedProduct> out = Collections.synchronizedList(new ArrayList<>());
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, fetchThreads));
        try {
            List<Future<?>> fs = new ArrayList<>();
            for (String u : productUrls) {
                fs.add(pool.submit(() -> {
                    ScrapedProduct sp = extractProduct(u);
                    if (sp != null) out.add(sp);
                }));
            }
            for (Future<?> f : fs) {
                try { f.get(timeoutMs + 5000L, TimeUnit.MILLISECONDS); } catch (Exception ignored) { /* skip slow/failed */ }
            }
        } finally {
            pool.shutdownNow();
        }
        log.info("StarTech: extracted {} products from {} URLs", out.size(), productUrls.size());
        return new ArrayList<>(out);
    }

    /** Every same-host URL in the sitemap (products + categories), deduped, in order. */
    private List<String> locsFromSitemap(String sitemapUrl, String base) {
        LinkedHashSet<String> locs = new LinkedHashSet<>();
        Document d = get(sitemapUrl);
        if (d == null) return new ArrayList<>(locs);
        for (Element loc : d.select("loc")) {
            String u = stripSlash(stripQuery(loc.text().trim()));
            if (u.startsWith(base) && !u.equals(base)) locs.add(u);
        }
        return new ArrayList<>(locs);
    }

    /** Product = long descriptive ROOT slug (brand-model-spec), not a short
     *  category slug (/acer-laptop) — StarTech/OpenCart convention. */
    private boolean isProductUrl(String url, String base) {
        if (url == null || !url.startsWith(base)) return false;
        String path = url.substring(base.length());
        if (!path.startsWith("/")) return false;
        path = path.substring(1);
        if (path.isEmpty() || path.contains("/")) return false;   // root-level only
        long hyphens = path.chars().filter(c -> c == '-').count();
        return path.length() >= 18 && hyphens >= 3;
    }

    private ScrapedProduct extractProduct(String url) {
        Document d = get(url);
        if (d == null) return null;
        Element priceEl = d.selectFirst(".price-new");
        if (priceEl == null) priceEl = d.selectFirst(".product-price");
        if (priceEl == null) return null;
        Double price = PriceParser.parseFirst(priceEl.text());
        if (price == null || price < 10) return null;
        Element oldEl = d.selectFirst(".price-old");
        Double original = oldEl == null ? null : PriceParser.parseFirst(oldEl.text());
        String name = meta(d, "og:title");
        if (name == null) { Element h1 = d.selectFirst("h1"); name = h1 == null ? null : h1.text().trim(); }
        if (name == null || name.isBlank()) return null;
        return ScrapedProduct.builder()
                .name(name)
                .price(price)
                .originalPrice(original != null && original > price ? original : null)
                .productUrl(url)
                .imageUrl(meta(d, "og:image"))
                .inStock(true)
                .build();
    }

    private static String meta(Document d, String prop) {
        Element m = d.selectFirst("meta[property=" + prop + "]");
        if (m == null) m = d.selectFirst("meta[name=" + prop + "]");
        String c = m == null ? null : m.attr("content").trim();
        return c == null || c.isBlank() ? null : c;
    }

    private Document get(String url) {
        try {
            Connection.Response r = Jsoup.connect(url)
                    .userAgent(UA).timeout(timeoutMs)
                    .ignoreHttpErrors(true).maxBodySize(8 * 1024 * 1024)
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .execute();
            if (r.statusCode() != 200) return null;
            return r.parse();
        } catch (Exception e) {
            return null;
        }
    }

    private static String stripSlash(String s) { return s.endsWith("/") ? s.substring(0, s.length() - 1) : s; }
    private static String stripQuery(String s) { int q = s.indexOf('?'); return q < 0 ? s : s.substring(0, q); }
}
