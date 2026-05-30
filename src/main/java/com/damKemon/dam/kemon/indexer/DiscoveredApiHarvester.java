package com.damKemon.dam.kemon.indexer;

import com.damKemon.dam.kemon.model.Shop;
import com.damKemon.dam.kemon.scraper.ScrapedProduct;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Replays a product feed the {@link ApiSniffer} already discovered for a shop —
 * over plain HTTP, no browser. This is the "learn once, harvest forever" half of
 * the self-discovering pipeline: the sniffer pays the one-time Playwright cost to
 * find the endpoint and records it on {@code Shop.discoveredApiUrl}; from then on
 * this harvester hits it directly, cheaply, every night.
 *
 * <p>If the recorded URL carries a query param (search feeds), we vary it across
 * a few seed terms for breadth; otherwise we just fetch it. Runs first in
 * {@link BulkIndexer}'s harvester loop, so a shop with a known feed never needs
 * the browser again. If the feed has gone stale (0 products) the loop falls
 * through to the normal pipeline + sniffer, which re-discovers it.
 */
@Service
public class DiscoveredApiHarvester implements ShopHarvester {

    private static final Logger log = LoggerFactory.getLogger(DiscoveredApiHarvester.class);
    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";

    /** Seed terms to vary a search-style feed's query param. */
    private static final List<String> SEEDS = List.of(
            "shirt", "phone", "rice", "jersey", "shoe", "watch", "bag", "oil", "toy", "book");
    private static final Pattern QUERY_PARAM =
            Pattern.compile("[?&](?:q|query|search|keyword|term)=([^&]*)", Pattern.CASE_INSENSITIVE);

    @Value("${discovered-api.max-products:400}") private int maxProducts;
    @Value("${discovered-api.timeout-ms:15000}") private int timeoutMs;
    @Value("${discovered-api.request-delay-ms:400}") private long requestDelayMs;

    private final ApiSniffer sniffer;

    public DiscoveredApiHarvester(ApiSniffer sniffer) {
        this.sniffer = sniffer;
    }

    @Override
    public boolean supports(Shop shop) {
        return shop != null && shop.getDiscoveredApiUrl() != null && !shop.getDiscoveredApiUrl().isBlank();
    }

    @Override
    public List<ScrapedProduct> harvest(Shop shop) {
        String feed = shop.getDiscoveredApiUrl();
        LinkedHashMap<String, ScrapedProduct> byKey = new LinkedHashMap<>();
        for (String url : urlsToHit(feed)) {
            if (byKey.size() >= maxProducts) break;
            String body = get(url);
            if (body == null) continue;
            for (ScrapedProduct sp : sniffer.extractProducts(body, shop.getBaseUrl())) {
                String key = sp.getProductUrl() != null ? sp.getProductUrl() : sp.getName();
                byKey.putIfAbsent(key, sp);
                if (byKey.size() >= maxProducts) break;
            }
        }
        if (!byKey.isEmpty()) {
            log.info("DiscoveredApi: shop '{}' replayed feed → {} products (no browser)", shop.getSlug(), byKey.size());
        }
        return new ArrayList<>(byKey.values());
    }

    /** The recorded URL, plus query-varied variants if it's a search feed. */
    private List<String> urlsToHit(String feed) {
        List<String> urls = new ArrayList<>();
        urls.add(feed);
        Matcher m = QUERY_PARAM.matcher(feed);
        if (m.find()) {
            for (String seed : SEEDS) {
                urls.add(feed.substring(0, m.start(1))
                        + URLEncoder.encode(seed, StandardCharsets.UTF_8)
                        + feed.substring(m.end(1)));
            }
        }
        return urls;
    }

    private String get(String url) {
        try {
            Connection.Response res = Jsoup.connect(url)
                    .userAgent(UA)
                    .header("Accept", "application/json, text/plain, */*")
                    .header("X-Requested-With", "XMLHttpRequest")
                    .ignoreContentType(true)
                    .ignoreHttpErrors(true)
                    .timeout(timeoutMs)
                    .maxBodySize(0)
                    .method(Connection.Method.GET)
                    .execute();
            if (res.statusCode() != 200) return null;
            try { Thread.sleep(requestDelayMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return res.body();
        } catch (Exception e) {
            log.debug("DiscoveredApi: fetch failed {}: {}", url, e.getMessage());
            return null;
        }
    }
}
