package com.damKemon.dam.kemon.indexer;

import com.damKemon.dam.kemon.model.Shop;
import com.damKemon.dam.kemon.model.ShopDiagnostic;
import com.damKemon.dam.kemon.repository.ShopDiagnosticRepository;
import com.damKemon.dam.kemon.repository.ShopRepository;
import com.damKemon.dam.kemon.scraper.ExtractorRegistry;
import com.damKemon.dam.kemon.scraper.GenericProductExtractor;
import com.damKemon.dam.kemon.scraper.ProductExtractor;
import com.damKemon.dam.kemon.scraper.ScrapedProduct;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The engine that stops me from being a bottleneck on broken shops.
 *
 * <p>After {@link BulkIndexer#indexShop} finishes a shop with zero
 * products extracted, this service kicks in. It:
 * <ol>
 *   <li>Throttles itself to once per 24h per shop — probes are not free.</li>
 *   <li>Re-runs the URL-discovery pipeline (sitemap → homepage →
 *       search-seed) to grab up to {@link #MAX_PROBE_URLS} candidate URLs.</li>
 *   <li>For each URL, runs <em>every</em> registered extractor (jsoup
 *       fetch only — JS rendering is a separate signal) and records which
 *       ones returned a valid product.</li>
 *   <li>Also fetches one sample URL twice — once jsoup, once with
 *       Playwright — and flags {@code jsImprovedExtraction=true} if only
 *       the JS render extracted anything. That auto-detects SPAs the
 *       operator forgot to mark with {@code requiresJs=true}.</li>
 *   <li>Sniffs the page for platform fingerprints (Shopify globals,
 *       WordPress generator meta, OpenCart route= URLs) and stores the
 *       detected platform so {@code shops.json} mismatches surface.</li>
 *   <li>Picks the extractor that worked on the most URLs as the winner.
 *       Writes it to {@code Shop.preferredExtractor} so the next run uses
 *       it directly via {@link ExtractorRegistry#pickForShop}.</li>
 *   <li>Persists the full audit trail as a {@link ShopDiagnostic} doc.</li>
 * </ol>
 *
 * <p>Net effect: when the nightly cron runs at 03:00, every shop at 0
 * products gets one self-diagnosis pass. Over a few nights the engine
 * either fixes itself (because a different extractor now matches the
 * site's HTML) or accumulates a structured "here's why this shop is
 * hopeless" record — surfaced via {@code /api/admin/shops/{slug}/diagnostic}.
 */
@Service
public class ScraperLearningService {

    private static final Logger log = LoggerFactory.getLogger(ScraperLearningService.class);

    /** Don't re-probe within this window — keeps the cost bounded. */
    private static final long LEARN_INTERVAL_HOURS = 24;
    /** Cap fetches per probe so a slow site can't stall the whole pass. */
    private static final int MAX_PROBE_URLS = 5;

    private final ShopRepository shops;
    private final ShopDiagnosticRepository diagnostics;
    private final ExtractorRegistry extractors;
    private final SitemapCrawler sitemapCrawler;
    private final HomepageCrawler homepageCrawler;
    private final SearchSeedCrawler searchSeedCrawler;
    private final com.damKemon.dam.kemon.scraper.BrowserFetcher browser;

    @Value("${scraper.user-agents:Mozilla/5.0}")
    private List<String> userAgents;

    @Value("${learner.enabled:true}")
    private boolean enabled;

    public ScraperLearningService(ShopRepository shops,
                                  ShopDiagnosticRepository diagnostics,
                                  ExtractorRegistry extractors,
                                  SitemapCrawler sitemapCrawler,
                                  HomepageCrawler homepageCrawler,
                                  SearchSeedCrawler searchSeedCrawler,
                                  com.damKemon.dam.kemon.scraper.BrowserFetcher browser) {
        this.shops = shops;
        this.diagnostics = diagnostics;
        this.extractors = extractors;
        this.sitemapCrawler = sitemapCrawler;
        this.homepageCrawler = homepageCrawler;
        this.searchSeedCrawler = searchSeedCrawler;
        this.browser = browser;
    }

    /**
     * Probe entry point — called by {@link BulkIndexer} after a 0-product
     * run. Returns the diagnostic so the indexer can log the outcome.
     * Returns {@code null} if the probe was skipped (throttled or disabled).
     */
    public ShopDiagnostic learnFromBrokenShop(Shop shop) {
        if (!enabled || shop == null) return null;
        if (shop.getLastLearnedAt() != null
                && shop.getLastLearnedAt().isAfter(LocalDateTime.now().minusHours(LEARN_INTERVAL_HOURS))) {
            log.debug("Learner: shop '{}' was probed within {}h, skipping",
                    shop.getSlug(), LEARN_INTERVAL_HOURS);
            return null;
        }

        log.info("Learner: probing 0-product shop '{}'", shop.getSlug());
        List<String> sampleUrls = discoverSampleUrls(shop);
        if (sampleUrls.isEmpty()) {
            ShopDiagnostic empty = ShopDiagnostic.builder()
                    .shopSlug(shop.getSlug())
                    .summary("No URLs discovered (sitemap empty, homepage yields no product links, search seeds returned nothing).")
                    .ts(Instant.now())
                    .build();
            persist(empty);
            stampShop(shop, null, null);
            return empty;
        }

        Map<String, Integer> extractorScores = new HashMap<>();
        List<ShopDiagnostic.UrlProbe> probes = new ArrayList<>();
        Boolean hasJsonLdAny = false;
        Boolean hasOgAny = false;
        Boolean jsImproved = false;
        String detectedPlatform = null;

        for (String url : sampleUrls) {
            ShopDiagnostic.UrlProbe probe = new ShopDiagnostic.UrlProbe();
            probe.setUrl(url);

            Document doc = safeJsoupFetch(url);
            if (doc == null) {
                probes.add(probe);
                continue;
            }
            probe.setPageTitle(doc.title());
            probe.setHtmlBytes(doc.html().length());

            // Schema sniffing
            if (hasJsonLdProduct(doc)) hasJsonLdAny = true;
            if (hasOgProduct(doc))     hasOgAny = true;
            if (detectedPlatform == null) detectedPlatform = sniffPlatform(doc, url);

            // Try the extractors that ACTUALLY claim this URL via supports()
            // — plus always test Generic as the universal fallback. Earlier
            // version ran every extractor unconditionally and credited the
            // Daraz/Pickaboo/etc. scrapers when their internal chain fell
            // through to GenericProductExtractor.parseJsonLd. Result: any
            // shop with JSON-LD ended up "matched" by every site-specific
            // and the learner picked whichever one Spring injected first
            // (Daraz, in practice). Skipping !supports() ones is the fix.
            Map<String, Boolean> perExtractor = new HashMap<>();
            for (ProductExtractor e : extractors.all()) {
                boolean isGeneric = "generic".equalsIgnoreCase(e.getSiteSlug());
                boolean shouldTry = isGeneric || e.supports(url);
                if (!shouldTry) continue;
                try {
                    ScrapedProduct sp = e.extract(url, false);
                    boolean ok = GenericProductExtractor.isValid(sp);
                    perExtractor.put(e.getSiteSlug(), ok);
                    if (ok) extractorScores.merge(e.getSiteSlug(), 1, Integer::sum);
                } catch (Exception ex) {
                    perExtractor.put(e.getSiteSlug(), false);
                }
            }
            probe.setExtractorResults(perExtractor);
            probes.add(probe);
        }

        // JS-rendering signal: pick one URL and render it with Playwright.
        // If jsoup extracted nothing but JS gets us a hit, the shop is a
        // SPA and we should set requiresJs=true.
        if (extractorScores.isEmpty() && !sampleUrls.isEmpty()) {
            String testUrl = sampleUrls.get(0);
            try {
                Document jsDoc = browser.fetchDocument(testUrl);
                if (jsDoc != null && hasJsonLdProduct(jsDoc)) jsImproved = true;
                if (jsDoc != null && hasOgProduct(jsDoc))     jsImproved = true;
            } catch (Exception e) {
                log.debug("Learner: JS probe failed on {}: {}", testUrl, e.getMessage());
            }
        }

        // Winner: the extractor that scored highest. Generic doesn't get
        // "credit" if site-specifics tied — we prefer specifics.
        String winner = pickWinner(extractorScores);
        String summary = buildSummary(extractorScores, winner, hasJsonLdAny, hasOgAny, jsImproved, sampleUrls.size());

        ShopDiagnostic diagnostic = ShopDiagnostic.builder()
                .shopSlug(shop.getSlug())
                .detectedPlatform(detectedPlatform)
                .hasJsonLd(hasJsonLdAny)
                .hasOgProduct(hasOgAny)
                .jsImprovedExtraction(jsImproved)
                .recommendedExtractor(winner)
                .samples(probes)
                .summary(summary)
                .ts(Instant.now())
                .build();
        persist(diagnostic);
        stampShop(shop, winner, detectedPlatform);

        // Auto-flip requiresJs when JS render made the difference.
        if (Boolean.TRUE.equals(jsImproved) && !Boolean.TRUE.equals(shop.getRequiresJs())) {
            shop.setRequiresJs(true);
            try { shops.save(shop); }
            catch (DataAccessException e) { log.debug("Learner: requiresJs auto-set save failed: {}", e.getMessage()); }
            log.info("Learner: auto-enabled requiresJs for '{}'", shop.getSlug());
        }

        log.info("Learner: shop '{}' → recommended extractor '{}' (sniff: jsonld={} og={} js+={} platform={})",
                shop.getSlug(), winner == null ? "none" : winner,
                hasJsonLdAny, hasOgAny, jsImproved, detectedPlatform);
        return diagnostic;
    }

    private List<String> discoverSampleUrls(Shop shop) {
        List<String> urls = new ArrayList<>();
        boolean js = Boolean.TRUE.equals(shop.getRequiresJs());
        try {
            if (shop.getSitemapUrl() != null && !shop.getSitemapUrl().isBlank()) {
                urls.addAll(sitemapCrawler.crawl(shop.getSitemapUrl()));
            }
            if (urls.isEmpty() && shop.getBaseUrl() != null) {
                urls.addAll(homepageCrawler.crawl(shop.getBaseUrl(), js));
            }
            if (urls.isEmpty()) {
                urls.addAll(searchSeedCrawler.crawl(shop, js));
            }
        } catch (Exception e) {
            log.debug("Learner: discovery failed for '{}': {}", shop.getSlug(), e.getMessage());
        }
        if (urls.size() > MAX_PROBE_URLS) {
            // Spread the probe across the URL list rather than taking the
            // first 5 — many shops list category pages at the top of the
            // sitemap and real products further down.
            List<String> spread = new ArrayList<>(MAX_PROBE_URLS);
            int step = Math.max(1, urls.size() / MAX_PROBE_URLS);
            for (int i = 0; i < urls.size() && spread.size() < MAX_PROBE_URLS; i += step) {
                spread.add(urls.get(i));
            }
            urls = spread;
        }
        return urls;
    }

    private Document safeJsoupFetch(String url) {
        try {
            return Jsoup.connect(url)
                    .userAgent(pickUa())
                    .timeout(15_000)
                    .followRedirects(true)
                    .ignoreHttpErrors(true)
                    .ignoreContentType(true)
                    .maxBodySize(0)
                    .get();
        } catch (Exception e) {
            log.debug("Learner: fetch failed for {}: {}", url, e.getMessage());
            return null;
        }
    }

    private static boolean hasJsonLdProduct(Document doc) {
        return doc.select("script[type=application/ld+json]").stream()
                .anyMatch(el -> {
                    String d = el.data();
                    return d.contains("\"@type\":\"Product\"")
                        || d.contains("\"@type\": \"Product\"");
                });
    }

    private static boolean hasOgProduct(Document doc) {
        return !doc.select("meta[property=og:type][content*=product]").isEmpty();
    }

    /**
     * Detect the platform from telltale fingerprints. Returns one of:
     * "shopify", "wordpress", "opencart", "magento", "wix",
     * "squarespace", or null when nothing matched.
     */
    private static String sniffPlatform(Document doc, String url) {
        String html = doc.html().toLowerCase();
        if (html.contains("shopify.shop") || html.contains("cdn.shopify.com") || html.contains("/cdn/shop/")) return "shopify";
        if (html.contains("wp-content/") || html.contains("wp-includes/") || html.contains("woocommerce")) return "wordpress";
        if (html.contains("catalog/view/theme/") || (url != null && url.contains("route=product"))) return "opencart";
        if (html.contains("mage/cookies") || html.contains("/static/version/frontend")) return "magento";
        if (html.contains("static.parastorage") || html.contains("wix.com")) return "wix";
        if (html.contains("static.squarespace.com")) return "squarespace";
        return null;
    }

    private static String pickWinner(Map<String, Integer> scores) {
        if (scores.isEmpty()) return null;
        int best = -1;
        String winner = null;
        for (Map.Entry<String, Integer> e : scores.entrySet()) {
            // Prefer non-generic on ties.
            int score = e.getValue();
            boolean isGeneric = "generic".equalsIgnoreCase(e.getKey());
            if (score > best || (score == best && winner != null && "generic".equalsIgnoreCase(winner) && !isGeneric)) {
                best = score;
                winner = e.getKey();
            }
        }
        return winner;
    }

    private static String buildSummary(Map<String, Integer> scores, String winner,
                                       Boolean jsonLd, Boolean og, Boolean jsImproved,
                                       int probedUrls) {
        if (winner == null) {
            if (Boolean.TRUE.equals(jsImproved)) {
                return "No extractor worked with jsoup, but JS render found product schema. "
                        + "Auto-enabled requiresJs — next run will use Playwright.";
            }
            if (!Boolean.TRUE.equals(jsonLd) && !Boolean.TRUE.equals(og)) {
                return "Pages have no JSON-LD or OG product schema. Likely a catalogue/brochure "
                        + "site without buyable prices — manual extractor required.";
            }
            return "Schema present (JSON-LD=" + jsonLd + ", OG=" + og + ") but no extractor "
                    + "produced a valid product. Selector tuning needed.";
        }
        return "Recommended extractor: " + winner + " (matched on "
                + scores.getOrDefault(winner, 0) + "/" + probedUrls + " probed URLs).";
    }

    private void persist(ShopDiagnostic d) {
        try { diagnostics.save(d); }
        catch (DataAccessException e) { log.warn("Learner: could not save diagnostic: {}", e.getMessage()); }
    }

    private void stampShop(Shop shop, String winner, String detectedPlatform) {
        shop.setPreferredExtractor(winner);
        if (detectedPlatform != null) shop.setDetectedPlatform(detectedPlatform);
        shop.setLastLearnedAt(LocalDateTime.now());
        try { shops.save(shop); }
        catch (DataAccessException e) { log.debug("Learner: shop stamp save failed: {}", e.getMessage()); }
    }

    private String pickUa() {
        if (userAgents == null || userAgents.isEmpty()) return "Mozilla/5.0";
        return userAgents.get(0);
    }
}
