package com.damKemon.dam.kemon.controller;

import com.damKemon.dam.kemon.scraper.ExtractorRegistry;
import com.damKemon.dam.kemon.scraper.GenericProductExtractor;
import com.damKemon.dam.kemon.scraper.ProductExtractor;
import com.damKemon.dam.kemon.scraper.ScrapedProduct;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Operator surface for iterating on scrapers without redeploying. Given a
 * candidate product URL, this endpoint:
 *
 * <ul>
 *   <li>Picks the extractor the indexer would route to today.</li>
 *   <li>Tries that extractor and reports what it pulls (or {@code null}).</li>
 *   <li>Falls through to every other registered extractor in order so the
 *       operator can see whether a different one (e.g. WooCommerce vs.
 *       Generic vs. Shopify) would have worked.</li>
 *   <li>Returns the page title + first 2KB of HTML as a sanity check so
 *       you can see whether the URL actually returned a product page or
 *       a 404/login/category listing.</li>
 * </ul>
 *
 * <p>Workflow when a shop is stuck at 0 products: hit this endpoint with a
 * couple of representative URLs, inspect which extractor (if any) returned
 * a valid result, then tune the matching extractor's selectors against
 * the {@code htmlSnippet} we ship back.
 */
@RestController
@RequestMapping("/api/admin/extractor")
public class AdminExtractorDebugController {

    private final ExtractorRegistry registry;
    private final GenericProductExtractor generic;

    public AdminExtractorDebugController(ExtractorRegistry registry, GenericProductExtractor generic) {
        this.registry = registry;
        this.generic = generic;
    }

    /**
     * {@code GET /api/admin/extractor/debug?url=https://...&useJs=false}.
     * Returns a structured report. Cheap to run — single HTTP fetch + a
     * handful of in-memory parses.
     */
    @GetMapping("/debug")
    public ResponseEntity<?> debug(@RequestParam("url") String url,
                                   @RequestParam(value = "useJs", defaultValue = "false") boolean useJs) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("url", url);

        // 1. Show which extractor would be picked.
        ProductExtractor picked = registry.pick(url);
        out.put("pickedExtractor", picked.getSiteSlug());
        out.put("pickedSiteName", picked.getSiteName());

        // 2. Run it.
        try {
            ScrapedProduct sp = picked.extract(url, useJs);
            out.put("pickedResult", sp == null ? null : Map.of(
                    "name", sp.getName() == null ? "" : sp.getName(),
                    "price", sp.getPrice() == null ? "null" : sp.getPrice().toString(),
                    "originalPrice", sp.getOriginalPrice() == null ? "null" : sp.getOriginalPrice().toString(),
                    "imageUrl", sp.getImageUrl() == null ? "" : sp.getImageUrl(),
                    "inStock", sp.getInStock() == null ? "true" : sp.getInStock().toString(),
                    "valid", GenericProductExtractor.isValid(sp)));
        } catch (Exception e) {
            out.put("pickedError", e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        // 3. Try every other registered extractor for comparison.
        List<Map<String, Object>> alternatives = new ArrayList<>();
        for (ProductExtractor e : registry.all()) {
            if (e == picked) continue;
            try {
                ScrapedProduct sp = e.extract(url, useJs);
                if (sp == null) continue;
                alternatives.add(Map.of(
                        "extractor", e.getSiteSlug(),
                        "name", sp.getName() == null ? "" : sp.getName(),
                        "price", sp.getPrice() == null ? "null" : sp.getPrice().toString(),
                        "valid", GenericProductExtractor.isValid(sp)
                ));
            } catch (Exception ex) {
                // ignored — alternatives are best-effort
            }
        }
        out.put("alternatives", alternatives);

        // 4. Page-fetch sanity check — what does the URL actually return?
        try {
            Document doc = Jsoup.connect(url)
                    .userAgent("Mozilla/5.0 DamKemon/1.0 ExtractorDebug")
                    .timeout(15000)
                    .followRedirects(true)
                    .ignoreHttpErrors(true)
                    .get();
            out.put("pageTitle", doc.title());
            out.put("hasJsonLdProduct", doc.select("script[type=application/ld+json]").stream()
                    .anyMatch(el -> el.data().contains("\"@type\":\"Product\"")
                                 || el.data().contains("\"@type\": \"Product\"")));
            out.put("hasOgProduct", !doc.select("meta[property=og:type][content*=product]").isEmpty());
            String html = doc.html();
            out.put("htmlSnippet", html.length() > 2048 ? html.substring(0, 2048) + "…" : html);
        } catch (Exception e) {
            out.put("fetchError", e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        return ResponseEntity.ok(out);
    }
}
