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

    /** Public entry point. Returns deduplicated product URLs, capped. */
    public List<String> crawl(String sitemapUrl) {
        if (sitemapUrl == null || sitemapUrl.isBlank()) return List.of();
        Set<String> productUrls = new LinkedHashSet<>();
        collect(sitemapUrl, productUrls, 0);
        log.info("Sitemap {}: {} product URLs after filtering", sitemapUrl, productUrls.size());
        return new ArrayList<>(productUrls);
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
        if (path == null) return false;
        if (NON_PRODUCT_PATH.matcher(path).find()) return false;
        return PRODUCT_PATH.matcher(path).find();
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
