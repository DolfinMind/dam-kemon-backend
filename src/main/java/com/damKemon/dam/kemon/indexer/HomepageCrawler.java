package com.damKemon.dam.kemon.indexer;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Fallback URL discovery for shops without a usable sitemap.xml.
 *
 * <p>Strategy:
 * <ol>
 *   <li>Fetch the shop's homepage HTML.</li>
 *   <li>Pull every anchor; classify by URL path:
 *     <ul>
 *       <li>{@code looksLikeProduct(url)} → add to result set (high-value).</li>
 *       <li>{@code looksLikeCategory(url)} → enqueue for one level of recursion.</li>
 *       <li>everything else → drop.</li>
 *     </ul>
 *   </li>
 *   <li>For each enqueued category URL (capped, deduped by host+path),
 *       fetch it and harvest more product URLs.</li>
 * </ol>
 *
 * <p>Stays inside the shop's base host so we never wander off into ads,
 * CDNs, or third-party sites.
 */
@Service
public class HomepageCrawler {

    private static final Logger log = LoggerFactory.getLogger(HomepageCrawler.class);

    private static final Pattern PRODUCT_PATH = Pattern.compile(
            "/(product|products|p|item|sku|book|catalog/product)/[^/?#]+", Pattern.CASE_INSENSITIVE);

    /** OpenCart style: {@code ?route=product/product&product_id=N}. */
    private static final Pattern PRODUCT_QS = Pattern.compile(
            "route=product/product", Pattern.CASE_INSENSITIVE);

    private static final Pattern CATEGORY_PATH = Pattern.compile(
            "/(category|categories|cat|c|shop|brand|brands|department|collection|collections)/[^/?#]+",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern CATEGORY_QS = Pattern.compile(
            "route=product/category", Pattern.CASE_INSENSITIVE);

    /** Obvious non-product paths we never want to recurse into. */
    private static final Pattern JUNK_PATH = Pattern.compile(
            "/(login|register|cart|checkout|account|wishlist|track|order|"
          + "wp-admin|wp-content|wp-includes|wp-json|feed|comments?|tag/|tags/|"
          + "author/|page/[0-9]|blog/|news/|press/|about|contact|privacy|terms|"
          + "policy|help|faq|support|sale\\.php|search|return|refund|career)/?",
            Pattern.CASE_INSENSITIVE);

    @Value("${indexer.sitemap-timeout-ms:15000}")
    private int timeoutMs;

    @Value("${indexer.max-urls-per-shop:1500}")
    private int maxUrlsPerShop;

    @Value("${indexer.homepage-max-categories:25}")
    private int maxCategoryPages;

    @Value("${indexer.user-agent:Mozilla/5.0 DamKemon/1.0}")
    private String userAgent;

    public List<String> crawl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) return List.of();
        String host;
        try { host = new URI(baseUrl).getHost(); } catch (Exception e) { return List.of(); }
        if (host == null) return List.of();

        Set<String> products = new LinkedHashSet<>();
        Set<String> categoriesToVisit = new LinkedHashSet<>();
        Set<String> visited = new LinkedHashSet<>();

        // Phase 1: scan homepage
        Document home = fetchDoc(baseUrl);
        if (home == null) {
            log.debug("Homepage crawl: {} unreachable", baseUrl);
            return List.of();
        }
        harvestLinks(home, host, products, categoriesToVisit, baseUrl);
        visited.add(canon(baseUrl));

        // Phase 2: walk discovered category pages, capped
        int cats = 0;
        for (String catUrl : categoriesToVisit) {
            if (cats >= maxCategoryPages) break;
            if (products.size() >= maxUrlsPerShop) break;
            if (visited.contains(canon(catUrl))) continue;
            visited.add(canon(catUrl));

            Document catDoc = fetchDoc(catUrl);
            cats++;
            if (catDoc == null) continue;
            // From category pages we ONLY pick product URLs — no further recursion
            // to keep the crawl bounded and deterministic.
            harvestLinks(catDoc, host, products, null, catUrl);
        }

        List<String> out = new ArrayList<>(products);
        log.info("HomepageCrawler {}: home + {} categories → {} product URLs",
                baseUrl, cats, out.size());
        return out;
    }

    private void harvestLinks(Document doc, String shopHost,
                              Set<String> productAcc,
                              Set<String> categoryAcc,  // null → don't collect categories
                              String pageUrl) {
        URI base = URI.create(doc.baseUri().isBlank() ? pageUrl : doc.baseUri());
        for (Element a : doc.select("a[href]")) {
            String raw = a.attr("href").trim();
            if (raw.isEmpty() || raw.startsWith("#") || raw.startsWith("javascript:")
                    || raw.startsWith("mailto:") || raw.startsWith("tel:")) continue;
            URI abs;
            try { abs = base.resolve(raw); } catch (Exception e) { continue; }
            String absUrl = abs.toString();
            String host = abs.getHost();
            if (host == null || !host.endsWith(shopHost.replaceFirst("^www\\.", ""))) continue;

            String path = abs.getPath() == null ? "" : abs.getPath();
            String qs = abs.getQuery() == null ? "" : abs.getQuery();
            String full = path + "?" + qs;

            if (JUNK_PATH.matcher(path).find()) continue;

            if (PRODUCT_PATH.matcher(path).find() || PRODUCT_QS.matcher(qs).find()) {
                productAcc.add(stripFragment(absUrl));
            } else if (categoryAcc != null
                    && (CATEGORY_PATH.matcher(path).find() || CATEGORY_QS.matcher(qs).find())) {
                categoryAcc.add(stripFragment(absUrl));
            }
        }
    }

    private Document fetchDoc(String url) {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(Duration.ofMillis(timeoutMs))
                    .build();
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("User-Agent", userAgent)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "en-US,en;q=0.9,bn;q=0.6")
                    .GET()
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 400) return null;
            return Jsoup.parse(resp.body(), url);
        } catch (Exception e) {
            return null;
        }
    }

    private static String stripFragment(String url) {
        int hash = url.indexOf('#');
        return hash < 0 ? url : url.substring(0, hash);
    }

    private static String canon(String url) {
        return stripFragment(url).replaceAll("/$", "");
    }
}
