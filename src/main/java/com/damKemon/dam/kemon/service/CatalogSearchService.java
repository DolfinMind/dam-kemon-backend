package com.damKemon.dam.kemon.service;

import com.damKemon.dam.kemon.dto.SearchResponse;
import com.damKemon.dam.kemon.intelligence.QueryClassifier;
import com.damKemon.dam.kemon.intelligence.QueryIntent;
import com.damKemon.dam.kemon.model.Product;
import com.damKemon.dam.kemon.repository.ProductRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Reads from the indexed {@code products} collection. Returns instantly —
 * no live scraping, no rate-limit visibility to the end-user.
 *
 * <p>Order of operations for {@link #search(String)}:
 * <ol>
 *   <li>Classify the query (categories, brands) for filtering downstream.</li>
 *   <li>Mongo {@code $text} search against the {@code name + description}
 *       text index. Falls back to a case-insensitive regex when text index
 *       isn't built yet.</li>
 *   <li>Re-rank by (score from token coverage, plus low-price boost) and
 *       return the top N grouped products. The merge across shops already
 *       happened at index time.</li>
 * </ol>
 *
 * <p>Results are cached in Caffeine for 60s (short, since the DB is fast
 * and we want price refreshes to surface quickly after re-indexing).
 */
@Service
public class CatalogSearchService {

    private static final Logger log = LoggerFactory.getLogger(CatalogSearchService.class);
    private static final Pattern NON_ALPHA = Pattern.compile("[^a-z0-9\\s]");

    private final ProductRepository productRepository;
    private final QueryClassifier classifier;

    @Value("${search.page-size:30}")
    private int pageSize;

    public CatalogSearchService(ProductRepository productRepository, QueryClassifier classifier) {
        this.productRepository = productRepository;
        this.classifier = classifier;
    }

    @Cacheable(
        value = "search",
        key = "#query == null ? '' : T(java.util.regex.Pattern).compile('\\\\s+').matcher(#query.trim()).replaceAll(' ').toLowerCase()",
        condition = "#query != null && #query.trim().length() >= 2",
        unless = "#result == null || #result.totalResults == null"
    )
    public SearchResponse search(String query) {
        QueryIntent intent = classifier.classify(query == null ? "" : query);
        // Always wrap in ArrayList — repo may return an immutable list (or List.of())
        // which would NPE/UOE when we try to sort it in place.
        List<Product> raw = new ArrayList<>(textOrRegexSearch(query));

        // Re-rank: token coverage of name vs query, then lowest-price tie-break
        String normalised = normalise(query);
        List<String> queryTokens = tokens(normalised);
        raw.sort(Comparator
                .comparingDouble((Product p) -> -tokenCoverage(p.getName(), queryTokens))
                .thenComparing(Comparator.comparingDouble(p -> p.getLowestPrice() == null ? Double.MAX_VALUE : p.getLowestPrice())));

        List<String> sitesSet = raw.stream()
                .flatMap(p -> p.getPrices() == null ? java.util.stream.Stream.empty() : p.getPrices().stream())
                .map(sp -> sp.getSiteName()).filter(java.util.Objects::nonNull).distinct().toList();

        return SearchResponse.builder()
                .query(query)
                .products(raw)
                .totalResults(raw.size())
                .sitesSearched(new ArrayList<>(sitesSet))
                .sitesSkipped(List.of())
                .detectedCategory(intent.primaryCategory().getLabel())
                .categories(intent.getCategories().stream().map(c -> c.getLabel()).collect(Collectors.toList()))
                .brands(intent.getBrands())
                .confidence(intent.getConfidence())
                .build();
    }

    /**
     * Lightweight prefix-style autocomplete: returns up to {@code limit}
     * distinct product names whose name contains the prefix (case-insensitive).
     */
    public List<Map<String, Object>> autocomplete(String prefix, int limit) {
        if (prefix == null || prefix.isBlank()) return List.of();
        String escaped = Pattern.quote(prefix.trim());
        try {
            Pageable page = PageRequest.of(0, Math.max(1, Math.min(limit, 20)));
            List<Product> hits = productRepository.findByNamePrefix(escaped, page);
            LinkedHashMap<String, Map<String, Object>> dedup = new LinkedHashMap<>();
            for (Product p : hits) {
                if (p.getName() == null) continue;
                dedup.computeIfAbsent(p.getName().toLowerCase(), k -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", p.getName());
                    m.put("slug", p.getSlug());
                    m.put("id", p.getId());
                    m.put("category", p.getCategory());
                    m.put("lowestPrice", p.getLowestPrice());
                    m.put("sellerCount", p.getPrices() == null ? 0 : p.getPrices().size());
                    m.put("imageUrl", p.getImageUrl());
                    return m;
                });
                if (dedup.size() >= limit) break;
            }
            return new ArrayList<>(dedup.values());
        } catch (DataAccessException e) {
            return List.of();
        }
    }

    /**
     * Three-pass search to balance precision and recall:
     *
     * <ol>
     *   <li>Mongo {@code $text} index — high precision, fast, ranks by tf-idf
     *       across name + description. Whole-word match only.</li>
     *   <li>Per-token regex {@code AND} — handles partial words ("tecno"
     *       inside "Tecno Spark 30") that {@code $text} misses, and lets
     *       multi-word queries combine. Results merged into the same pool.</li>
     *   <li>Single-blob substring regex — last resort when neither matches.</li>
     * </ol>
     *
     * <p>We always run pass 2 and merge with pass 1 so partial-prefix hits
     * complement the text-index hits.
     */
    private List<Product> textOrRegexSearch(String query) {
        if (query == null || query.isBlank()) return List.of();
        Pageable page = PageRequest.of(0, pageSize);

        Map<String, Product> merged = new LinkedHashMap<>();

        // Pass 1: Mongo $text
        try {
            for (Product p : productRepository.textSearch(query, page)) {
                if (p.getId() != null) merged.putIfAbsent(p.getId(), p);
            }
        } catch (Exception e) {
            log.debug("Mongo $text search failed ({}), continuing with regex", e.getMessage());
        }

        // Pass 2: per-token AND regex (so "tecno spark" matches "Tecno Spark 30")
        List<String> tokens = tokens(normalise(query));
        if (!tokens.isEmpty() && merged.size() < pageSize) {
            try {
                for (Product p : productRepository.findByNamePrefix(buildAndRegex(tokens), page)) {
                    if (p.getId() != null) merged.putIfAbsent(p.getId(), p);
                }
            } catch (Exception e) {
                log.debug("Per-token regex failed: {}", e.getMessage());
            }
        }

        // Pass 3: case-insensitive substring on the raw query
        if (merged.size() < pageSize) {
            try {
                for (Product p : productRepository.findByNameContainingIgnoreCase(query)) {
                    if (p.getId() != null) merged.putIfAbsent(p.getId(), p);
                }
            } catch (DataAccessException e) {
                log.warn("Catalog search: Mongo unreachable ({})", e.getMessage());
            }
        }
        return new ArrayList<>(merged.values());
    }

    /** Build a Mongo regex that requires every token to appear (in any order). */
    private static String buildAndRegex(List<String> tokens) {
        StringBuilder sb = new StringBuilder();
        for (String t : tokens) {
            sb.append("(?=.*").append(Pattern.quote(t)).append(")");
        }
        sb.append(".+");
        return sb.toString();
    }

    private static double tokenCoverage(String name, List<String> queryTokens) {
        if (name == null || queryTokens.isEmpty()) return 0;
        String lname = name.toLowerCase();
        long matched = queryTokens.stream().filter(lname::contains).count();
        return (double) matched / queryTokens.size();
    }

    private static String normalise(String s) {
        if (s == null) return "";
        return NON_ALPHA.matcher(s.toLowerCase()).replaceAll(" ").replaceAll("\\s+", " ").trim();
    }

    private static List<String> tokens(String s) {
        if (s == null || s.isBlank()) return List.of();
        return java.util.Arrays.stream(s.split("\\s+")).filter(t -> t.length() >= 2).toList();
    }
}
