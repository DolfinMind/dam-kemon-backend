package com.damKemon.dam.kemon.controller;

import com.damKemon.dam.kemon.model.Product;
import com.damKemon.dam.kemon.repository.ProductRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Serves {@code /sitemap.xml} and {@code /robots.txt} at the API root so a
 * reverse-proxy fronting the SPA can route these to the backend. Lists
 * the canonical web URL of every product so Google can index our pages.
 *
 * <p>Capped at 50,000 URLs per sitemap (the W3C sitemap spec maximum).
 * Beyond that we'd need to shard into multiple sitemaps + an index — at
 * current catalog size we're well under.
 */
@RestController
public class SitemapController {

    private static final int MAX_URLS = 50_000;

    private final ProductRepository productRepository;

    @Value("${auth.web-url:http://localhost:5173}")
    private String webUrl;

    public SitemapController(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    @GetMapping(value = "/sitemap.xml", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> sitemap() {
        StringBuilder sb = new StringBuilder(64 * 1024);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n");

        String base = webUrl.replaceAll("/$", "");
        String today = LocalDate.now().format(DateTimeFormatter.ISO_DATE);

        // Static pages
        appendUrl(sb, base + "/", today, "daily", "1.0");
        appendUrl(sb, base + "/guides", today, "weekly", "0.8");
        appendUrl(sb, base + "/guides/why-one-search-beats-ten-browser-tabs", today, "weekly", "0.6");
        appendUrl(sb, base + "/guides/buying-from-unknown-seller-use-protect", today, "weekly", "0.6");
        appendUrl(sb, base + "/guides/how-trust-score-spots-fake-low-prices", today, "weekly", "0.6");
        appendUrl(sb, base + "/sellers", today, "weekly", "0.5");
        appendUrl(sb, base + "/compare", today, "monthly", "0.3");
        appendUrl(sb, base + "/submit-shop", today, "monthly", "0.3");

        try {
            int count = 0;
            for (Product p : productRepository.findAll()) {
                if (count++ >= MAX_URLS) break;
                String slug = (p.getSlug() == null || p.getSlug().isBlank()) ? p.getId() : p.getSlug();
                if (slug == null) continue;
                String lastmod = today;
                appendUrl(sb, base + "/product/" + escape(slug), lastmod, "daily", "0.7");
            }
        } catch (DataAccessException ignored) { /* empty sitemap is fine */ }

        sb.append("</urlset>\n");
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_XML).body(sb.toString());
    }

    @GetMapping(value = "/robots.txt", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> robots() {
        String base = webUrl.replaceAll("/$", "");
        StringBuilder sb = new StringBuilder();
        sb.append("User-agent: *\n");
        sb.append("Allow: /\n");
        sb.append("Disallow: /admin\n");
        sb.append("Disallow: /api/admin/\n");
        sb.append("\n");
        sb.append("Sitemap: ").append(base).append("/sitemap.xml\n");
        return ResponseEntity.ok(sb.toString());
    }

    private static void appendUrl(StringBuilder sb, String loc, String lastmod, String freq, String pri) {
        sb.append("  <url>\n");
        sb.append("    <loc>").append(escape(loc)).append("</loc>\n");
        sb.append("    <lastmod>").append(lastmod).append("</lastmod>\n");
        sb.append("    <changefreq>").append(freq).append("</changefreq>\n");
        sb.append("    <priority>").append(pri).append("</priority>\n");
        sb.append("  </url>\n");
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
