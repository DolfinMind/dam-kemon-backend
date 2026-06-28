package com.damKemon.dam.kemon.indexer;

import com.damKemon.dam.kemon.config.AppRole;
import com.damKemon.dam.kemon.model.PendingShop;
import com.damKemon.dam.kemon.model.Shop;
import com.damKemon.dam.kemon.repository.PendingShopRepository;
import com.damKemon.dam.kemon.repository.ShopRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Closes the autonomous-catalog loop so the operator never has to babysit the
 * shop list. Runs weekly (before the nightly indexer) and does three things:
 *
 * <ol>
 *   <li><b>Discover</b> — {@link ShopDiscoveryService#discover()} scans the
 *       e-CAB / BASIS directories and queues shops we don't have yet into
 *       {@code pending_shops}.</li>
 *   <li><b>Auto-activate (with a guardrail)</b> — each pending candidate gets a
 *       cheap probe (sitemap/homepage → how many product URLs?). Ones that look
 *       like a real store are promoted to an active {@link Shop} (so the next
 *       nightly {@link BulkIndexer} run indexes them); obvious non-shops are
 *       auto-rejected so we stop re-probing them.</li>
 *   <li><b>Revive</b> — shops {@link com.damKemon.dam.kemon.service.ShopHealthService}
 *       previously auto-blocked (3+ dead nightly runs) are re-probed; if a site
 *       has come back online it's flipped back to active.</li>
 * </ol>
 *
 * <p>Auto-<i>deactivation</i> is already handled inline by ShopHealthService
 * during indexing, so this scheduler only adds the discovery + activation +
 * revival halves of the loop. Disable with {@code DISCOVERY_ENABLED=false}.
 */
@Service
public class ShopLifecycleScheduler {

    private static final Logger log = LoggerFactory.getLogger(ShopLifecycleScheduler.class);

    private final ShopDiscoveryService discovery;
    private final PendingShopRepository pendingRepo;
    private final ShopRepository shopRepository;
    private final SitemapCrawler sitemapCrawler;
    private final HomepageCrawler homepageCrawler;
    private final AppRole appRole;

    @Value("${discovery.enabled:true}")
    private boolean enabled;

    /** Min product URLs a probe must find before we trust a site is a real shop. */
    @Value("${discovery.min-product-urls:5}")
    private int minProductUrls;

    /** Cap probes per run so a flood of candidates can't stall the scheduler. */
    @Value("${discovery.max-per-run:20}")
    private int maxPerRun;

    public ShopLifecycleScheduler(ShopDiscoveryService discovery,
                                  PendingShopRepository pendingRepo,
                                  ShopRepository shopRepository,
                                  SitemapCrawler sitemapCrawler,
                                  HomepageCrawler homepageCrawler,
                                  AppRole appRole) {
        this.discovery = discovery;
        this.pendingRepo = pendingRepo;
        this.shopRepository = shopRepository;
        this.sitemapCrawler = sitemapCrawler;
        this.homepageCrawler = homepageCrawler;
        this.appRole = appRole;
    }

    /**
     * Weekly, 01:00 Sunday by default — two hours before the 03:00 indexer, so
     * freshly-activated shops get crawled the same night.
     */
    @Scheduled(cron = "${discovery.cron:0 0 1 * * SUN}")
    public void weekly() {
        if (appRole.isWeb()) return;
        if (!enabled) {
            log.info("ShopLifecycle: skipped — DISCOVERY_ENABLED is false");
            return;
        }
        try {
            Map<String, Object> summary = runOnce();
            log.info("ShopLifecycle: weekly run finished {}", summary);
        } catch (Exception e) {
            log.error("ShopLifecycle: weekly run crashed", e);
        }
    }

    /** Discover → auto-activate validated candidates → revive recovered shops. */
    public Map<String, Object> runOnce() {
        Map<String, Object> discoverResult;
        try {
            discoverResult = discovery.discover();
        } catch (Exception e) {
            log.warn("ShopLifecycle: discovery failed: {}", e.getMessage());
            discoverResult = Map.of("error", String.valueOf(e.getMessage()));
        }
        int promoted = promotePending();
        int revived = reviveBlocked();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("discovery", discoverResult);
        out.put("autoActivated", promoted);
        out.put("revived", revived);
        return out;
    }

    /** Probe each pending candidate; activate the real shops, reject the junk. */
    private int promotePending() {
        List<PendingShop> pending;
        try {
            pending = pendingRepo.findAll();
        } catch (DataAccessException e) {
            log.warn("ShopLifecycle: cannot list pending shops: {}", e.getMessage());
            return 0;
        }
        int promoted = 0, probed = 0;
        for (PendingShop p : pending) {
            if (!"pending".equals(p.getStatus())) continue;
            if (probed >= maxPerRun) break;
            probed++;

            int found = probe(p.getBaseUrl(), p.getSitemapUrl(), false);
            if (found >= minProductUrls) {
                if (activate(p, found)) promoted++;
            } else if (found == 0) {
                // No product URLs at all → almost certainly not a usable shop.
                // Reject so it leaves the queue and we stop re-probing it weekly.
                p.setStatus("rejected");
                p.setReviewNote("auto-rejected: probe found no product URLs");
                p.setReviewedAt(LocalDateTime.now());
                savePending(p);
            }
            // 1..min-1 product URLs: borderline — leave pending, re-probe next week.
        }
        return promoted;
    }

    private boolean activate(PendingShop p, int found) {
        String base = (p.getName() == null || p.getName().isBlank()) ? p.getBaseUrl() : p.getName();
        String slug = slugify(base);
        if (slug.isBlank()) return false;
        try {
            if (shopRepository.findBySlug(slug).isPresent()) {
                // Already a known shop — close out the duplicate candidate.
                p.setStatus("approved");
                p.setReviewNote("auto: shop already exists for slug '" + slug + "'");
                p.setReviewedAt(LocalDateTime.now());
                savePending(p);
                return false;
            }
            LocalDateTime now = LocalDateTime.now();
            Shop s = Shop.builder()
                    .slug(slug)
                    .name(p.getName())
                    .baseUrl(p.getBaseUrl())
                    .sitemapUrl(p.getSitemapUrl())
                    .platform(p.getPlatform())
                    .categories(p.getCategories() == null ? new ArrayList<>() : p.getCategories())
                    .status("active")
                    .health("active")
                    .createdAt(now)
                    .updatedAt(now)
                    .build();
            shopRepository.save(s);

            p.setStatus("approved");
            p.setReviewNote("auto-approved: probe found " + found + " product URLs");
            p.setReviewedAt(now);
            savePending(p);

            log.info("ShopLifecycle: auto-activated shop '{}' ({} product URLs found)", slug, found);
            return true;
        } catch (DataAccessException e) {
            log.warn("ShopLifecycle: failed to activate '{}': {}", slug, e.getMessage());
            return false;
        }
    }

    /** Re-probe auto-blocked shops; reactivate any whose site is healthy again. */
    private int reviveBlocked() {
        List<Shop> blocked;
        try {
            blocked = shopRepository.findByStatus("blocked");
        } catch (DataAccessException e) {
            return 0;
        }
        int revived = 0, probed = 0;
        for (Shop s : blocked) {
            if (probed >= maxPerRun) break;
            probed++;
            int found = probe(s.getBaseUrl(), s.getSitemapUrl(), Boolean.TRUE.equals(s.getRequiresJs()));
            if (found >= minProductUrls) {
                s.setStatus("active");
                s.setHealth("degraded");
                s.setConsecutiveFailures(0);
                s.setNeedsRetry(true);
                s.setUpdatedAt(LocalDateTime.now());
                try {
                    shopRepository.save(s);
                    revived++;
                    log.info("ShopLifecycle: revived blocked shop '{}' ({} product URLs found)", s.getSlug(), found);
                } catch (DataAccessException ignored) { /* best effort */ }
            }
        }
        return revived;
    }

    /**
     * Cheap "is this a real shop?" probe: count product URLs the sitemap (or, as
     * a fallback, the homepage) exposes. No per-product extraction — just URL
     * discovery, so it's fast and polite.
     */
    private int probe(String baseUrl, String sitemapUrl, boolean js) {
        try {
            List<String> urls = new ArrayList<>();
            if (sitemapUrl != null && !sitemapUrl.isBlank()) {
                urls = sitemapCrawler.crawl(sitemapUrl);
            }
            if ((urls == null || urls.isEmpty()) && baseUrl != null && !baseUrl.isBlank()) {
                urls = homepageCrawler.crawl(baseUrl, js);
            }
            return urls == null ? 0 : urls.size();
        } catch (Exception e) {
            log.debug("ShopLifecycle: probe failed for {}: {}", baseUrl, e.getMessage());
            return 0;
        }
    }

    private void savePending(PendingShop p) {
        try { pendingRepo.save(p); }
        catch (DataAccessException e) { log.debug("ShopLifecycle: pending save failed: {}", e.getMessage()); }
    }

    private static String slugify(String name) {
        if (name == null) return "";
        return name.toLowerCase()
                .replaceAll("https?://", "")
                .replaceAll("[^a-z0-9\\s-]", "")
                .replaceAll("\\s+", "-")
                .replaceAll("-+", "-")
                .replaceAll("^-|-$", "");
    }
}
