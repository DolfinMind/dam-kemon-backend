package com.damKemon.dam.kemon.indexer;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * Walks a shop's sitemap tree and returns all product page URLs.
 *
 * <p>Handles:
 * <ul>
 *   <li>{@code <sitemapindex>} containing nested {@code <sitemap><loc>...} entries
 *       — we recurse one level deep.</li>
 *   <li>{@code <urlset>} containing flat {@code <url><loc>...} entries
 *       — these are the leaves.</li>
 *   <li>Gzipped sitemap files ({@code .xml.gz}).</li>
 * </ul>
 *
 * <p>Filters out obvious non-product URLs (category pages, blog posts, tag
 * pages, author pages) by URL-path heuristic. Returns at most
 * {@link #maxUrlsPerShop} URLs per shop to bound nightly crawl cost.
 */
@Service
public class SitemapCrawler {

    private static final Logger log = LoggerFactory.getLogger(SitemapCrawler.class);

    /** Heuristic patterns for URLs we want to keep (product pages). */
    private static final Pattern PRODUCT_PATH = Pattern.compile(
            "/(product|products|p|item|sku)/[^/?#]+", Pattern.CASE_INSENSITIVE);

    /** Patterns we drop without fetching (category/blog/etc.). */
    private static final Pattern NON_PRODUCT_PATH = Pattern.compile(
            "/(category|categories|tag|tags|brand|brands|page|blog|news|author|"
                    + "wp-content|wp-includes|wp-admin|feed|comment|search|"
                    + "shop/page/|product-category|product_tag|attachment)/", Pattern.CASE_INSENSITIVE);

    @Value("${indexer.sitemap-timeout-ms:15000}")
    private int timeoutMs;

    @Value("${indexer.max-urls-per-shop:1500}")
    private int maxUrlsPerShop;

    @Value("${indexer.user-agent:Mozilla/5.0 DamKemon/1.0 (+contact: hello@damkemon.com)}")
    private String userAgent;

    /** Common sitemap locations to probe when the configured one is missing/empty. */
    private static final List<String> COMMON_SITEMAP_PATHS = List.of(
            "/sitemap_index.xml", "/sitemap.xml", "/wp-sitemap.xml",
            "/product-sitemap.xml", "/sitemap/sitemap.xml", "/pub/sitemap.xml");

    /** Public entry point. Returns deduplicated product URLs, capped. */
    public List<String> crawl(String sitemapUrl) {
        if (sitemapUrl == null || sitemapUrl.isBlank()) return List.of();
        Set<String> productUrls = new LinkedHashSet<>();
        collect(sitemapUrl, productUrls, 0);
        log.info("Sitemap {}: {} product URLs after filtering", sitemapUrl, productUrls.size());
        return new ArrayList<>(productUrls);
    }

    /**
     * Auto-discover a shop's sitemap when the configured URL is missing or
     * yields nothing. Checks {@code robots.txt} for {@code Sitemap:} directives,
     * then a list of common paths (Yoast {@code sitemap_index.xml}, WP core
     * {@code wp-sitemap.xml}, Magento, etc.). Returns product URLs from the
     * first candidate that produces any.
     */
    public List<String> discoverAndCrawl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) return List.of();
        String root = rootOf(baseUrl);
        if (root == null) return List.of();

        LinkedHashSet<String> candidates = new LinkedHashSet<>(sitemapsFromRobots(root));
        for (String path : COMMON_SITEMAP_PATHS) candidates.add(root + path);

        for (String candidate : candidates) {
            Set<String> products = new LinkedHashSet<>();
            collect(candidate, products, 0);
            if (!products.isEmpty()) {
                log.info("Sitemap auto-discovery: {} → {} product URLs via {}",
                        root, products.size(), candidate);
                return new ArrayList<>(products);
            }
        }
        return List.of();
    }

    /** Parse {@code Sitemap:} directives out of robots.txt. */
    private List<String> sitemapsFromRobots(String root) {
        List<String> out = new ArrayList<>();
        try {
            byte[] body = fetchBytes(root + "/robots.txt");
            if (body == null) return out;
            for (String line : new String(body, StandardCharsets.UTF_8).split("\\r?\\n")) {
                String l = line.trim();
                if (l.toLowerCase().startsWith("sitemap:")) {
                    String u = l.substring(8).trim();
                    if (!u.isBlank()) out.add(u);
                }
            }
        } catch (Exception e) {
            log.debug("robots.txt fetch failed for {}: {}", root, e.getMessage());
        }
        return out;
    }

    private static String rootOf(String url) {
        try {
            URI u = URI.create(url);
            if (u.getScheme() == null || u.getHost() == null) return null;
            return u.getScheme() + "://" + u.getHost();
        } catch (Exception e) { return null; }
    }

    /** Recursive walk. Depth-limit avoids infinite loops on bad sitemaps. */
    private void collect(String url, Set<String> productUrls, int depth) {
        if (depth > 3) return;
        if (productUrls.size() >= maxUrlsPerShop) return;

        byte[] body;
        try {
            body = fetchBytes(url);
        } catch (Exception e) {
            log.debug("Sitemap fetch failed for {}: {}", url, e.getMessage());
            return;
        }
        if (body == null || body.length == 0) return;

        String xml = decompressIfNeeded(url, body);
        Document doc = Jsoup.parse(xml, url, Parser.xmlParser());

        // Walk every <loc> element. Differentiate sitemap-index entries
        // (parent tag name "sitemap") from leaf URL entries (parent "url")
        // — robust against jsoup XML-mode quirks with namespaces / whitespace.
        for (Element loc : doc.select("loc")) {
            if (productUrls.size() >= maxUrlsPerShop) break;
            String txt = loc.text().trim();
            if (txt.isEmpty()) continue;
            String parentName = loc.parent() == null ? "" : loc.parent().tagName().toLowerCase();
            if ("sitemap".equals(parentName)) {
                // Nested sitemap reference — recurse one level deeper.
                collect(txt, productUrls, depth + 1);
            } else {
                // Leaf URL entry (parent "url", or any other container).
                if (looksLikeProductUrl(txt)) productUrls.add(txt);
            }
        }
    }

    /** Heuristic — keep if path matches PRODUCT_PATH and doesn't match NON_PRODUCT_PATH. */
    private boolean looksLikeProductUrl(String url) {
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) return false;
        String path;
        try { path = URI.create(url).getPath(); }
        catch (Exception e) { return false; }
        if (path == null || path.isBlank() || "/".equals(path)) return false;
        if (NON_PRODUCT_PATH.matcher(path).find()) return false;
        if (PRODUCT_PATH.matcher(path).find()) return true;
        // Flat product slugs used by Magento / many custom shops, e.g.
        // "/asus-rog-strix-b660-a-gaming-wifi-d4-motherboard.html". The hyphen
        // requirement separates real products from single-word category pages
        // ("/gaming.html"); any non-product that slips through just extracts
        // nothing and costs only a fetch. This is what was dropping ~all of the
        // Magento shops (their sitemaps carry 0 "/product/" URLs).
        String last = path.substring(path.lastIndexOf('/') + 1);
        long hyphens = last.chars().filter(c -> c == '-').count();
        if (last.endsWith(".html") && hyphens >= 1 && last.length() > 10) return true;
        if (hyphens >= 2 && last.length() >= 15 && !last.contains(".")) return true;
        return false;
    }

    private byte[] fetchBytes(String url) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofMillis(timeoutMs))
                .build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(timeoutMs))
                .header("User-Agent", userAgent)
                .header("Accept", "application/xml,text/xml,*/*")
                .header("Accept-Encoding", "gzip")
                .GET()
                .build();
        HttpResponse<byte[]> resp = client.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (resp.statusCode() >= 400) {
            throw new RuntimeException("HTTP " + resp.statusCode());
        }
        return resp.body();
    }

    private String decompressIfNeeded(String url, byte[] body) {
        boolean isGz = url.endsWith(".gz") || (body.length > 2 && (body[0] & 0xff) == 0x1f && (body[1] & 0xff) == 0x8b);
        if (!isGz) return new String(body, StandardCharsets.UTF_8);
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(body))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            // Fall back to treating it as plain bytes.
            return new String(body, StandardCharsets.UTF_8);
        }
    }
}
