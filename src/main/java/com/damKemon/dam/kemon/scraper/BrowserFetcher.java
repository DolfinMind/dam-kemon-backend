package com.damKemon.dam.kemon.scraper;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.WaitUntilState;
import jakarta.annotation.PreDestroy;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Single shared Playwright instance + Chromium browser. Per request we open a
 * fresh BrowserContext (private cookie jar / cache) and a single Page, render
 * the URL, then close the context.
 *
 * Use {@link #isAvailable()} to check before calling — if Chromium isn't
 * installed yet ("playwright install chromium"), this stays disabled and
 * scrapers fall back to jsoup.
 *
 * Disable entirely with BROWSER_ENABLED=false.
 */
@Service
public class BrowserFetcher {

    private static final Logger log = LoggerFactory.getLogger(BrowserFetcher.class);

    @Value("${browser.enabled:true}")
    private boolean enabled;

    @Value("${browser.headless:true}")
    private boolean headless;

    @Value("${browser.timeout-ms:25000}")
    private int timeoutMs;

    @Value("${browser.wait-until:DOMCONTENTLOADED}")
    private String waitUntil;

    private volatile Playwright playwright;
    private volatile Browser browser;
    private final Object initLock = new Object();
    private final AtomicBoolean initFailed = new AtomicBoolean(false);

    private final AtomicLong fetches = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();

    /** Whether the browser is enabled AND not in a failed-init state. */
    public boolean isAvailable() {
        return enabled && !initFailed.get();
    }

    /**
     * Fetch rendered HTML. Returns null on failure — callers should check and
     * fall back to a jsoup fetch (which will work for static sites).
     *
     * <p>Playwright's Java binding is single-threaded; concurrent calls to
     * the same Browser hit "Cannot find object to call __adopt__" errors.
     * We serialise every fetch behind a global lock. Throughput drops to
     * one render at a time, which is acceptable since we only use Playwright
     * for a small set of SPA shops.
     */
    public synchronized String fetchHtml(String url) {
        if (!enabled) return null;
        try {
            ensureBrowser();
        } catch (Exception e) {
            log.error("Playwright init failed (run: 'mvn exec:java -e -Dexec.mainClass=\"com.microsoft.playwright.CLI\" -Dexec.args=\"install chromium\"' from project root). Falling back to jsoup. Error: {}",
                    e.getMessage());
            initFailed.set(true);
            return null;
        }
        fetches.incrementAndGet();
        long t0 = System.currentTimeMillis();
        try (BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                        .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36")
                        .setLocale("en-US")
                        .setExtraHTTPHeaders(java.util.Map.of(
                                "Accept-Language", "en-US,en;q=0.9,bn;q=0.6"
                        )));
             Page page = context.newPage()) {
            page.setDefaultTimeout(timeoutMs);
            WaitUntilState wait = parseWait(waitUntil);
            page.navigate(url, new Page.NavigateOptions().setWaitUntil(wait).setTimeout(timeoutMs));
            // small explicit settle so async JS that fetches results has a chance
            page.waitForTimeout(800);
            String html = page.content();
            log.debug("Playwright fetched {} ({} bytes) in {}ms", url,
                    html == null ? 0 : html.length(), System.currentTimeMillis() - t0);
            return html;
        } catch (Exception e) {
            failures.incrementAndGet();
            log.warn("Playwright fetch failed for {} in {}ms: {}", url,
                    System.currentTimeMillis() - t0, e.getMessage());
            return null;
        }
    }

    /** Fetch + parse with jsoup. Returns null if rendering fails. */
    public Document fetchDocument(String url) {
        String html = fetchHtml(url);
        return html == null ? null : Jsoup.parse(html, url);
    }

    /**
     * Render a page and capture the JSON bodies it loads over the network — the
     * raw material the {@code ApiSniffer} mines to auto-discover a shop's hidden
     * product API. Returns up to {@code maxCaptures} JSON responses ≥
     * {@code minBytes}. Null/empty-safe; returns an empty list if disabled.
     */
    public synchronized List<JsonCapture> captureJson(String url, int maxCaptures, int minBytes) {
        if (!enabled) return List.of();
        try {
            ensureBrowser();
        } catch (Exception e) {
            initFailed.set(true);
            return List.of();
        }
        // onResponse fires on Playwright's event thread — use a concurrent list.
        java.util.concurrent.CopyOnWriteArrayList<JsonCapture> out = new java.util.concurrent.CopyOnWriteArrayList<>();
        long t0 = System.currentTimeMillis();
        try (BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                        .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36")
                        .setLocale("en-US")
                        .setExtraHTTPHeaders(java.util.Map.of("Accept-Language", "en-US,en;q=0.9,bn;q=0.6")));
             Page page = context.newPage()) {
            page.setDefaultTimeout(timeoutMs);
            page.onResponse(resp -> {
                try {
                    if (out.size() >= maxCaptures) return;
                    String ct = resp.headers().getOrDefault("content-type", "");
                    if (ct == null || !ct.toLowerCase().contains("json")) return;
                    String body = resp.text();
                    if (body == null || body.length() < minBytes) return;
                    out.add(new JsonCapture(resp.url(), body));
                } catch (Exception ignored) { /* body not retrievable for this response */ }
            });
            page.navigate(url, new Page.NavigateOptions().setWaitUntil(parseWait(waitUntil)).setTimeout(timeoutMs));
            page.waitForTimeout(2500); // give client-side XHR/fetch product calls time to fire
            log.debug("captureJson {} → {} JSON responses in {}ms", url, out.size(), System.currentTimeMillis() - t0);
            return new ArrayList<>(out);
        } catch (Exception e) {
            log.warn("captureJson failed for {}: {}", url, e.getMessage());
            return new ArrayList<>(out);
        }
    }

    /** A JSON network response captured during a render: its URL + raw body. */
    public record JsonCapture(String url, String body) {}

    public Stats stats() {
        return new Stats(enabled, !initFailed.get(), fetches.get(), failures.get());
    }

    public static record Stats(boolean enabled, boolean ready, long fetches, long failures) {}

    private void ensureBrowser() {
        if (browser != null) return;
        synchronized (initLock) {
            if (browser != null) return;
            log.info("Launching Playwright Chromium (headless={})…", headless);
            playwright = Playwright.create();
            browser = playwright.chromium().launch(
                    new BrowserType.LaunchOptions()
                            .setHeadless(headless)
                            .setArgs(Arrays.asList(
                                    "--disable-blink-features=AutomationControlled",
                                    "--disable-dev-shm-usage",
                                    "--no-sandbox"
                            ))
            );
            log.info("Playwright Chromium ready");
        }
    }

    private WaitUntilState parseWait(String value) {
        if (value == null) return WaitUntilState.DOMCONTENTLOADED;
        try {
            return WaitUntilState.valueOf(value.trim().toUpperCase());
        } catch (Exception e) {
            return WaitUntilState.DOMCONTENTLOADED;
        }
    }

    @PreDestroy
    public void shutdown() {
        try {
            if (browser != null) browser.close();
        } catch (Exception ignored) {}
        try {
            if (playwright != null) playwright.close();
        } catch (Exception ignored) {}
        log.info("Playwright shut down");
    }
}
