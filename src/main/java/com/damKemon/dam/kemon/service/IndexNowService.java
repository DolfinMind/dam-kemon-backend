package com.damKemon.dam.kemon.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Fire-and-forget IndexNow pings (Bing/Yandex) for product pages the indexer
 * creates or updates, so they're crawled within minutes instead of whenever
 * the sitemap is next fetched. Slugs queue in memory and flush in batches;
 * failures are logged and dropped — IndexNow is best-effort, the nightly
 * sitemap remains the backstop. Off unless INDEXNOW_ENABLED=true.
 */
@Service
public class IndexNowService {

    private static final Logger log = LoggerFactory.getLogger(IndexNowService.class);
    private static final String ENDPOINT = "https://api.indexnow.org/indexnow";
    private static final int MAX_PER_POST = 10_000; // IndexNow per-request cap
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Queue<String> pending = new ConcurrentLinkedQueue<>();
    private final HttpClient http = HttpClient.newHttpClient();

    @Value("${indexnow.enabled:false}")
    private boolean enabled;

    @Value("${indexnow.key:}")
    private String key;

    @Value("${auth.web-url:https://damkemon.com}")
    private String webUrl;

    /** Queue a product slug for the next flush. No-op unless indexnow.enabled. */
    public void submit(String slug) {
        if (enabled && slug != null && !slug.isBlank()) pending.add(slug);
    }

    /** Drain the queue and POST batched URLs. A merge burst (nightly crawl)
     *  accumulates between ticks, so one POST carries many URLs. */
    @Scheduled(initialDelay = 60_000, fixedDelay = 120_000)
    public void flush() {
        if (pending.isEmpty()) return;
        String base = webUrl.replaceAll("/$", "");
        LinkedHashSet<String> urls = new LinkedHashSet<>();
        for (String slug; (slug = pending.poll()) != null; ) urls.add(base + "/product/" + slug);
        List<String> all = new ArrayList<>(urls);
        for (int i = 0; i < all.size(); i += MAX_PER_POST) {
            post(all.subList(i, Math.min(i + MAX_PER_POST, all.size())));
        }
    }

    // package-private so the unit test can capture batches without HTTP
    void post(List<String> urls) {
        try {
            String body = JSON.writeValueAsString(Map.of(
                    "host", URI.create(webUrl).getHost(),
                    "key", key,
                    "urlList", urls));
            HttpRequest req = HttpRequest.newBuilder(URI.create(ENDPOINT))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            http.sendAsync(req, HttpResponse.BodyHandlers.ofString()).whenComplete((res, err) -> {
                if (err != null) log.warn("IndexNow: submit failed: {}", err.getMessage());
                else if (res.statusCode() >= 300) log.warn("IndexNow: HTTP {} submitting {} urls", res.statusCode(), urls.size());
                else log.info("IndexNow: submitted {} urls", urls.size());
            });
        } catch (Exception e) {
            log.warn("IndexNow: submit failed: {}", e.getMessage());
        }
    }
}
