package com.damKemon.dam.kemon.indexer;

import com.damKemon.dam.kemon.model.PendingShop;
import com.damKemon.dam.kemon.repository.PendingShopRepository;
import com.damKemon.dam.kemon.repository.ShopRepository;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Walks public BD-ecommerce directories (e-cab and BASIS member lists) to
 * propose new shops we don't already index. Anything we find is upserted
 * into the {@code pending_shops} collection with {@code status=pending}
 * so a human reviews before it crawls live.
 *
 * <p>Conservatively narrow: we only follow links that look like absolute
 * URLs to a different domain than the directory itself, dedupe by host,
 * and skip social-media or marketplace links (facebook, instagram, etc).
 */
@Service
public class ShopDiscoveryService {

    private static final Logger log = LoggerFactory.getLogger(ShopDiscoveryService.class);

    /**
     * Public BD-ecommerce directories we follow. e-cab + BASIS are the
     * two industry associations that publish member lists. Broader news pages
     * produced far more publishers and press-release sites than real shops;
     * product-led SERP discovery covers the long tail with stronger intent.
     *
     * Adding a source is cheap: anything that links to other domains
     * works. We dedupe by host so re-running the discover is idempotent.
     */
    private static final List<String> SOURCES = List.of(
            "https://e-cab.net/members/",
            "https://basis.org.bd/members"
    );

    private static final Set<String> BLOCKLIST_HOST_SUBSTRINGS = Set.of(
            "facebook.com", "fb.com", "instagram.com", "twitter.com", "x.com",
            "linkedin.com", "youtube.com", "youtu.be", "wikipedia.org",
            "daraz.com", "google.com", "amazon.com", "alibaba.com",
            "e-cab.net", "basis.org.bd",
            "thedailystar.net", "tbsnews.net", "dhakatribune.com",
            "prothomalo.com", "futurestartup.com",
            "bdnews24.com", "newagebd.net", "thefinancialexpress.com.bd",
            "prnewswire.com", "newswire", "medium.com", "github.com", "gitlab.com",
            "archive.org", "wordpress.com", "blogspot.com",
            "bb.org.bd", "btrc.gov.bd",
            // SERP noise: price-info/aggregator/spec sites that aren't shops
            "gsmarena", "mobiledokan.com", "mobilemaya", "techtunes", "bikroy",
            "crunchbase", "top10place", "placedigger", "infoisinfo", "asiafirms",
            "mi.com", "locate.apple", "apple.com", "samsung.com"
    );

    /**
     * TLD heuristic — we want hosts likely to be BD shops. We accept any
     * {@code .com.bd} / {@code .net.bd} / {@code .bd}, plus generic
     * {@code .com} hosts that appear in a BD-focused source page. Generic
     * non-BD links from news articles (apple.com, samsung.com) get
     * filtered downstream when we look at the host.
     */
    private static final Set<String> BD_TLDS = Set.of(".com.bd", ".net.bd", ".bd", ".org.bd");

    private final PendingShopRepository pendingRepo;
    private final ShopRepository shopRepo;

    /** Optional search-API endpoint template containing {q} (e.g. SerpAPI / Bing /
     *  Google CSE JSON). When set, {@link #discoverViaSearch()} finds shops by
     *  searching popular products and harvesting the BD shop domains from results
     *  — far higher yield than domain-guessing. No-op when blank. */
    @Value("${discovery.search-api-url:}")
    private String searchApiUrl;

    /** Product/category queries whose SERPs surface BD shops carrying that SKU. */
    private static final List<String> SEED_QUERIES = List.of(
        "iphone 15 price in bangladesh shop", "samsung galaxy price in bangladesh shop",
        "xiaomi redmi phone price in bangladesh shop", "rtx 4060 graphics card price in bangladesh",
        "ssd price in bangladesh computer shop", "gaming pc desktop price in bangladesh shop",
        "laptop price in bangladesh online shop", "macbook price in bangladesh reseller",
        "monitor price in bangladesh computer shop", "dslr mirrorless camera price in bangladesh shop",
        "cctv ip camera price in bangladesh shop", "wireless earbuds tws price in bangladesh shop",
        "bluetooth speaker price in bangladesh shop", "power bank price in bangladesh shop",
        "smartwatch price in bangladesh shop", "wifi router price in bangladesh shop",
        "printer toner price in bangladesh shop", "keyboard mouse gaming price in bangladesh shop",
        "used laptop price in bangladesh shop", "phone case screen protector price in bangladesh",
        "computer shop chittagong bangladesh online", "online gadget shop bangladesh list");

    public ShopDiscoveryService(PendingShopRepository pendingRepo, ShopRepository shopRepo) {
        this.pendingRepo = pendingRepo;
        this.shopRepo = shopRepo;
    }

    /**
     * Run a discovery pass. Returns the summary: candidates found, already
     * known, queued for review.
     */
    public Map<String, Object> discover() {
        Set<String> existingHosts = listExistingHosts();
        Set<String> pendingHosts = listPendingHosts();
        Map<String, String> candidates = new LinkedHashMap<>();

        for (String source : SOURCES) {
            try {
                Document doc = Jsoup.connect(source)
                        .userAgent("Mozilla/5.0 DamKemon/1.0 ShopDiscovery")
                        .timeout(20_000)
                        .get();
                for (Element a : doc.select("a[href]")) {
                    String href = a.absUrl("href");
                    if (href.isBlank()) continue;
                    String host = hostOf(href);
                    if (host == null || host.isBlank()) continue;
                    if (!isCandidateHost(host)) continue;
                    String root = "https://" + stripWww(host);
                    if (existingHosts.contains(stripWww(host))) continue;
                    if (pendingHosts.contains(stripWww(host))) continue;
                    candidates.putIfAbsent(stripWww(host), root);
                }
            } catch (Exception e) {
                log.warn("ShopDiscovery: source {} failed: {}", source, e.getMessage());
            }
        }

        int queued = 0;
        for (Map.Entry<String, String> e : candidates.entrySet()) {
            String host = e.getKey();
            String root = e.getValue();
            PendingShop p = PendingShop.builder()
                    .name(host)
                    .baseUrl(root)
                    .sitemapUrl(root + "/sitemap.xml")
                    .notes("auto-discovered from e-cab/BASIS directory")
                    .status("pending")
                    .submittedAt(LocalDateTime.now())
                    .build();
            try { pendingRepo.save(p); queued++; }
            catch (DataAccessException ignored) {}
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sourcesScanned", SOURCES.size());
        out.put("candidatesSeen", candidates.size());
        out.put("queuedForReview", queued);
        log.info("ShopDiscovery: scanned {} sources → {} new candidates → {} queued",
                SOURCES.size(), candidates.size(), queued);
        return out;
    }

    /**
     * SERP-based discovery: run {@link #SEED_QUERIES} through a configured search
     * API and harvest BD shop domains from the result URLs. This finds the long
     * tail of shops we'd never guess (and surfaces exactly the shops carrying a
     * given SKU → more sellers per product). Opt-in via {@code discovery.search-api-url};
     * queued candidates are probed + activated by {@code ShopLifecycleScheduler}.
     */
    public Map<String, Object> discoverViaSearch() {
        Map<String, Object> out = new LinkedHashMap<>();
        if (searchApiUrl == null || searchApiUrl.isBlank()) {
            out.put("skipped", "discovery.search-api-url not set");
            return out;
        }
        Set<String> existingHosts = listExistingHosts();
        Set<String> pendingHosts = listPendingHosts();
        Map<String, String> candidates = new LinkedHashMap<>();
        java.util.regex.Pattern urlPat =
                java.util.regex.Pattern.compile("https?://([a-zA-Z0-9.-]+\\.[a-zA-Z]{2,})");
        for (String q : SEED_QUERIES) {
            try {
                String enc = java.net.URLEncoder.encode(q, java.nio.charset.StandardCharsets.UTF_8);
                String url = searchApiUrl.replace("{q}", enc);
                String body = Jsoup.connect(url)
                        .ignoreContentType(true).ignoreHttpErrors(true)
                        .userAgent("DamKemon/1.0 SerpDiscovery")
                        .timeout(20_000).maxBodySize(0).execute().body();
                java.util.regex.Matcher m = urlPat.matcher(body);
                while (m.find()) {
                    String host = stripWww(m.group(1).toLowerCase());
                    if (host == null || host.isBlank() || !isCandidateHost(host)) continue;
                    if (existingHosts.contains(host) || pendingHosts.contains(host)) continue;
                    candidates.putIfAbsent(host, "https://" + host);
                }
                Thread.sleep(300);
            } catch (Exception e) {
                log.warn("SERP discovery: query '{}' failed: {}", q, e.getMessage());
            }
        }
        int queued = 0;
        for (Map.Entry<String, String> e : candidates.entrySet()) {
            try {
                pendingRepo.save(PendingShop.builder()
                        .name(e.getKey()).baseUrl(e.getValue())
                        .sitemapUrl(e.getValue() + "/sitemap.xml")
                        .notes("serp-discovered").status("pending")
                        .submittedAt(LocalDateTime.now()).build());
                queued++;
            } catch (DataAccessException ignored) { /* dup/transient */ }
        }
        out.put("queriesRun", SEED_QUERIES.size());
        out.put("candidatesFound", candidates.size());
        out.put("queuedForReview", queued);
        log.info("SERP discovery: {} queries → {} candidates → {} queued for probe",
                SEED_QUERIES.size(), candidates.size(), queued);
        return out;
    }

    /** Accept .bd TLDs plus generic .com/.net (most BD shops); the probe gate in
     *  ShopLifecycleScheduler filters non-shops, and the blocklist drops globals. */
    private static boolean isBdShopHost(String host) {
        for (String t : BD_TLDS) if (host.endsWith(t)) return true;
        return host.endsWith(".com") || host.endsWith(".net") || host.endsWith(".xyz");
    }

    static boolean isCandidateHost(String host) {
        return host != null && !host.isBlank() && !isBlocked(host) && isBdShopHost(host);
    }

    private Set<String> listExistingHosts() {
        Set<String> out = new HashSet<>();
        try {
            shopRepo.findAll().forEach(s -> {
                String h = hostOf(s.getBaseUrl());
                if (h != null) out.add(stripWww(h));
            });
        } catch (DataAccessException ignored) {}
        return out;
    }

    private Set<String> listPendingHosts() {
        Set<String> out = new HashSet<>();
        try {
            pendingRepo.findAll().forEach(p -> {
                String h = hostOf(p.getBaseUrl());
                if (h != null) out.add(stripWww(h));
            });
        } catch (DataAccessException ignored) {}
        return out;
    }

    private static boolean isBlocked(String host) {
        String h = host.toLowerCase();
        for (String b : BLOCKLIST_HOST_SUBSTRINGS) if (h.contains(b)) return true;
        return false;
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
