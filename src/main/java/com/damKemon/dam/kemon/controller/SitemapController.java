package com.damKemon.dam.kemon.controller;

import com.damKemon.dam.kemon.repository.ProductRepository;
import com.damKemon.dam.kemon.service.CategoryFocusService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

/**
 * Serves {@code /sitemap.xml} and {@code /robots.txt} at the API root so a
 * reverse-proxy fronting the SPA can route these to the backend. Lists
 * the canonical web URL of every product so Google can index our pages.
 *
 * <p>The catalog outgrew the 50,000-URL spec cap (products past it were
 * silently invisible to Google), so {@code /sitemap.xml} is now a sitemap
 * INDEX whose children live at {@code /sitemap.xml?page=N} — same path, so
 * the reverse proxy needs no new routing rule, and root-level children may
 * reference any URL on the host.
 */
@RestController
public class SitemapController {

    private static final int CHUNK = 10_000;

    private final ProductRepository productRepository;
    private final CategoryFocusService categoryFocus;

    // Default to the public domain, NOT localhost: prod never set AUTH_WEB_URL,
    // which shipped a sitemap full of http://localhost:5173 URLs to Google.
    @Value("${auth.web-url:https://damkemon.com}")
    private String webUrl;

    public SitemapController(ProductRepository productRepository, CategoryFocusService categoryFocus) {
        this.productRepository = productRepository;
        this.categoryFocus = categoryFocus;
    }

    @GetMapping(value = {"/sitemap.xml", "/api/seo/sitemap.xml"}, produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> sitemap(@RequestParam(value = "page", required = false) Integer page) {
        String base = webUrl.replaceAll("/$", "");
        String today = LocalDate.now().format(DateTimeFormatter.ISO_DATE);

        long total = 0;
        try {
            total = categoryFocus.isEnabled()
                    ? productRepository.countByCategoryIn(categoryFocus.allowedLabels())
                    : productRepository.count();
        } catch (DataAccessException ignored) { /* index with just page 0 is fine */ }
        int pages = Math.max(1, (int) ((total + CHUNK - 1) / CHUNK));

        if (page == null) {
            // Sitemap index — the one URL Search Console / robots.txt point at.
            StringBuilder sb = new StringBuilder(4 * 1024);
            sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
            sb.append("<sitemapindex xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n");
            for (int i = 0; i < pages; i++) {
                sb.append("  <sitemap>\n")
                  .append("    <loc>").append(base).append("/sitemap.xml?page=").append(i).append("</loc>\n")
                  .append("    <lastmod>").append(today).append("</lastmod>\n")
                  .append("  </sitemap>\n");
            }
            sb.append("</sitemapindex>\n");
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_XML).body(sb.toString());
        }

        if (page < 0 || page >= pages) return ResponseEntity.notFound().build();

        StringBuilder sb = new StringBuilder(64 * 1024);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n");

        if (page == 0) {
            appendUrl(sb, base + "/", today, "daily", "1.0");
            appendUrl(sb, base + "/browse", today, "daily", "0.8");
            for (String category : List.of(
                    "smartphones", "laptops", "desktops & pc", "monitors",
                    "headphones & audio", "accessories")) {
                appendUrl(sb, base + "/category/" + urlPath(category), today, "daily", "0.8");
            }
            appendUrl(sb, base + "/guides", today, "weekly", "0.8");
            appendUrl(sb, base + "/guides/why-one-search-beats-ten-browser-tabs", today, "weekly", "0.6");
            appendUrl(sb, base + "/guides/buying-from-unknown-seller-check-risk", today, "weekly", "0.6");
            appendUrl(sb, base + "/guides/how-trust-score-spots-fake-low-prices", today, "weekly", "0.6");
            appendUrl(sb, base + "/trending", today, "daily", "0.6");
            appendUrl(sb, base + "/drops", today, "daily", "0.6");
        }

        try {
            // id+slug projection, one CHUNK per request — never loads the whole
            // products collection (with its prices arrays) into heap. Sorted by
            // id so pagination is stable across chunk fetches.
            Set<String> categories = categoryFocus.allowedLabels();
            List<ProductRepository.SlugView> products = categoryFocus.isEnabled()
                    ? productRepository.findSlugViewsByCategoryIn(
                            categories, PageRequest.of(page, CHUNK, Sort.by("id")))
                    : productRepository.findAllSlugViews(PageRequest.of(page, CHUNK, Sort.by("id")));
            for (ProductRepository.SlugView p : products) {
                String slug = (p.getSlug() == null || p.getSlug().isBlank()) ? p.getId() : p.getSlug();
                if (slug == null) continue;
                appendUrl(sb, base + "/product/" + escape(slug), today, "daily", "0.7");
            }
        } catch (DataAccessException ignored) { /* partial sitemap is fine */ }

        sb.append("</urlset>\n");
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_XML).body(sb.toString());
    }

    // IndexNow ownership proof: https://damkemon.com/{key}.txt must return the
    // key. Spring resolves the ${indexnow.key} placeholder in the mapping at
    // startup, so this only ever matches the real key file, not any *.txt.
    @Value("${indexnow.key:}")
    private String indexNowKey;

    @GetMapping(value = "/${indexnow.key:}.txt", produces = MediaType.TEXT_PLAIN_VALUE)
    public String indexNowKeyFile() {
        return indexNowKey;
    }

    @GetMapping(value = "/robots.txt", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> robots() {
        String base = webUrl.replaceAll("/$", "");
        StringBuilder sb = new StringBuilder();
        sb.append("User-agent: *\n");
        sb.append("Allow: /\n");
        sb.append("Disallow: /admin\n");
        sb.append("Disallow: /account\n");
        sb.append("Allow: /api/r/\n");
        sb.append("Disallow: /api/\n");
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

    private static String urlPath(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
