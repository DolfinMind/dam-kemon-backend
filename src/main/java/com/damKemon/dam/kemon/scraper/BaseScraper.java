package com.damKemon.dam.kemon.scraper;

import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * HTTP fetch helpers shared by every {@link ProductExtractor}. Provides:
 * <ul>
 *   <li>retry with exponential backoff</li>
 *   <li>rotating User-Agent</li>
 *   <li>per-host minimum delay between requests (politeness)</li>
 *   <li>timeout + redirect handling</li>
 *   <li>URL-encode helper</li>
 * </ul>
 *
 * <p>Subclasses are extractors that pull a single product from a URL. The
 * generic schema.org extractor and the site-specific ones (Daraz, Pickaboo,
 * Startech) all extend this.
 */
public abstract class BaseScraper {

    protected final Logger log = LoggerFactory.getLogger(getClass());

    @Value("${scraper.timeout:15000}")
    protected int timeoutMs;

    @Value("${scraper.retry-attempts:2}")
    protected int retryAttempts;

    @Value("${scraper.retry-delay-ms:800}")
    protected long retryDelayMs;

    @Value("${scraper.request-delay-ms:600}")
    protected long requestDelayMs;

    @Value("${scraper.user-agents:Mozilla/5.0}")
    protected List<String> userAgents;

    private static final ConcurrentHashMap<String, AtomicLong> LAST_HIT = new ConcurrentHashMap<>();

    /** Fetch a URL with retry, UA rotation, and per-host throttle. */
    protected Document fetch(String url) throws IOException {
        throttleHost(url);
        IOException last = null;
        for (int attempt = 0; attempt <= retryAttempts; attempt++) {
            try {
                return connect(url).get();
            } catch (IOException e) {
                last = e;
                long sleep = retryDelayMs * (1L << attempt) + ThreadLocalRandom.current().nextLong(200);
                log.debug("attempt {} failed for {} — retrying in {}ms ({})",
                        attempt + 1, url, sleep, e.getMessage());
                try { Thread.sleep(sleep); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt(); break;
                }
            }
        }
        throw last != null ? last : new IOException("fetch failed: " + url);
    }

    protected Connection connect(String url) {
        String ua = userAgents == null || userAgents.isEmpty()
                ? "Mozilla/5.0"
                : userAgents.get(ThreadLocalRandom.current().nextInt(userAgents.size()));
        return Jsoup.connect(url)
                .userAgent(ua)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9,bn;q=0.6")
                .header("Cache-Control", "no-cache")
                .timeout(timeoutMs)
                .followRedirects(true)
                .ignoreHttpErrors(true)
                .ignoreContentType(true)
                .maxBodySize(0);
    }

    protected String encode(String query) {
        return URLEncoder.encode(query == null ? "" : query, StandardCharsets.UTF_8);
    }

    private void throttleHost(String url) {
        try {
            String host = java.net.URI.create(url).getHost();
            if (host == null) return;
            AtomicLong last = LAST_HIT.computeIfAbsent(host, h -> new AtomicLong(0));
            long now = System.currentTimeMillis();
            long elapsed = now - last.get();
            if (elapsed < requestDelayMs) {
                Thread.sleep(requestDelayMs - elapsed);
            }
            last.set(System.currentTimeMillis());
        } catch (Exception ignored) {}
    }
}
