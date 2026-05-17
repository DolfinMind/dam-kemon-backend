package com.damKemon.dam.kemon.scraper;

import com.damKemon.dam.kemon.intelligence.ProductCategory;
import com.damKemon.dam.kemon.intelligence.QueryClassifier;
import com.damKemon.dam.kemon.intelligence.QueryIntent;
import com.damKemon.dam.kemon.intelligence.ResultValidator;
import com.damKemon.dam.kemon.intelligence.ResultValidator.ScoredResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Intelligent search engine. Steps per query:
 *   1. QueryClassifier → ProductCategory + brands + model tokens
 *   2. Route ONLY to scrapers whose categories overlap (or who handle generic queries)
 *   3. Fan out concurrently with timeout
 *   4. ResultValidator drops garbage (no price, wrong price range, low similarity)
 *   5. Return per-site SCORED results
 */
@Service
public class ScraperEngine {

    private static final Logger log = LoggerFactory.getLogger(ScraperEngine.class);
    // Aggressive timeout: we'd rather return 6 results in 5s than 7 in 15s.
    // Slow scrapers get skipped this round; cache hides this on subsequent calls.
    private static final long PER_SCRAPER_TIMEOUT_MS = 5_000;

    private final List<EcommerceScraper> scrapers;
    private final QueryClassifier classifier;
    private final ResultValidator validator;
    private final ExecutorService pool;

    public ScraperEngine(List<EcommerceScraper> scrapers,
                         QueryClassifier classifier,
                         ResultValidator validator) {
        this.scrapers = scrapers;
        this.classifier = classifier;
        this.validator = validator;
        this.pool = Executors.newFixedThreadPool(Math.max(4, scrapers.size()));
        log.info("ScraperEngine initialized with {} scrapers: {}",
                scrapers.size(),
                scrapers.stream().map(EcommerceScraper::getSiteName).collect(Collectors.joining(", ")));
    }

    /** Returns per-site SCORED results, ranked by relevance. */
    public EngineSearchResult searchAll(String query) {
        QueryIntent intent = classifier.classify(query);
        List<EcommerceScraper> routed = routeForIntent(intent);

        log.info("Query='{}' category={} routed to {}/{} scrapers: {}",
                query, intent.getCategories(), routed.size(), scrapers.size(),
                routed.stream().map(EcommerceScraper::getSiteName).collect(Collectors.joining(", ")));

        Map<String, List<ScoredResult>> bySite = new ConcurrentHashMap<>();
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (EcommerceScraper scraper : routed) {
            futures.add(CompletableFuture.runAsync(() -> {
                try {
                    long t0 = System.currentTimeMillis();
                    List<ScrapedProduct> raw = scraper.search(query);
                    List<ScoredResult> scored = validator.validate(raw, intent);
                    bySite.put(scraper.getSiteName(), scored);
                    log.info("[{}] raw={} kept={} in {}ms",
                            scraper.getSiteName(), raw.size(), scored.size(),
                            System.currentTimeMillis() - t0);
                } catch (Exception e) {
                    log.error("[{}] failed: {}", scraper.getSiteName(), e.getMessage());
                    bySite.put(scraper.getSiteName(), Collections.emptyList());
                }
            }, pool));
        }

        try {
            // Total wait = per-scraper timeout + 1s grace. Anything not done by
            // then is abandoned for this request (their result lands in cache
            // on a later call).
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(PER_SCRAPER_TIMEOUT_MS + 1_000, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.warn("Some scrapers timed out for query '{}': {}", query, e.getMessage());
        }

        return new EngineSearchResult(intent, bySite);
    }

    private List<EcommerceScraper> routeForIntent(QueryIntent intent) {
        ProductCategory primary = intent.primaryCategory();
        Set<ProductCategory> intentCategories = new HashSet<>(intent.getCategories());

        // GENERAL queries (low confidence / no category match): use all generalist scrapers
        boolean noStrongSignal = intent.getConfidence() < 0.2 || primary == ProductCategory.GENERAL;
        if (noStrongSignal) {
            return scrapers.stream()
                    .filter(EcommerceScraper::handlesGeneralQueries)
                    .collect(Collectors.toList());
        }

        // Otherwise: keep scrapers whose categories overlap OR who explicitly handle generic queries
        List<EcommerceScraper> matched = scrapers.stream()
                .filter(s -> {
                    if (s.handlesGeneralQueries()) {
                        // Generalist marketplaces still join unless we're in a very narrow category
                        // they typically don't sell (e.g., a specialized book search shouldn't hit them).
                        return s.getSupportedCategories().contains(primary);
                    }
                    Set<ProductCategory> supported = s.getSupportedCategories();
                    return supported.stream().anyMatch(intentCategories::contains);
                })
                .collect(Collectors.toList());

        // Edge case: classifier was confident but no scrapers match → fall back to generalists.
        if (matched.isEmpty()) {
            return scrapers.stream()
                    .filter(EcommerceScraper::handlesGeneralQueries)
                    .collect(Collectors.toList());
        }
        return matched;
    }

    public List<Map<String, Object>> getAvailableSites() {
        return scrapers.stream()
                .map(s -> {
                    Map<String, Object> info = new LinkedHashMap<>();
                    info.put("name", s.getSiteName());
                    info.put("slug", s.getSiteSlug());
                    info.put("baseUrl", s.getBaseUrl());
                    info.put("categories", s.getSupportedCategories().stream()
                            .map(Enum::name).collect(Collectors.toList()));
                    info.put("general", s.handlesGeneralQueries());
                    info.put("available", true);  // skip live ping to keep this endpoint fast
                    return info;
                })
                .collect(Collectors.toList());
    }

    public List<EcommerceScraper> getScrapers() {
        return Collections.unmodifiableList(scrapers);
    }

    /** Result wrapper returned to SearchService. */
    public static class EngineSearchResult {
        public final QueryIntent intent;
        public final Map<String, List<ScoredResult>> bySite;
        public EngineSearchResult(QueryIntent intent, Map<String, List<ScoredResult>> bySite) {
            this.intent = intent;
            this.bySite = bySite;
        }
    }
}
