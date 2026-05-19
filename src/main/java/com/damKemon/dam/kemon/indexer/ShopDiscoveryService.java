package com.damKemon.dam.kemon.indexer;

import com.damKemon.dam.kemon.model.PendingShop;
import com.damKemon.dam.kemon.repository.PendingShopRepository;
import com.damKemon.dam.kemon.repository.ShopRepository;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final List<String> SOURCES = List.of(
            "https://e-cab.net/members/",
            "https://basis.org.bd/members"
    );

    private static final Set<String> BLOCKLIST_HOST_SUBSTRINGS = Set.of(
            "facebook.com", "fb.com", "instagram.com", "twitter.com", "x.com",
            "linkedin.com", "youtube.com", "youtu.be", "wikipedia.org",
            "daraz.com", "google.com", "amazon.com", "alibaba.com",
            "e-cab.net", "basis.org.bd"
    );

    private final PendingShopRepository pendingRepo;
    private final ShopRepository shopRepo;

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
                    if (isBlocked(host)) continue;
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
