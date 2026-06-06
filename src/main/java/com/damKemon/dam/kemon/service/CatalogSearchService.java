package com.damKemon.dam.kemon.service;

import com.damKemon.dam.kemon.dto.SearchResponse;
import com.damKemon.dam.kemon.intelligence.QueryClassifier;
import com.damKemon.dam.kemon.intelligence.QueryExpander;
import com.damKemon.dam.kemon.intelligence.QueryIntent;
import com.damKemon.dam.kemon.intelligence.TrigramIndex;
import com.damKemon.dam.kemon.intelligence.TrigramSearchIndex;
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

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * User-facing search over the indexed {@code products} collection.
 *
 * <p>This service is built around one principle: <b>never return zero results
 * just because the user typed a different word than the indexer stored.</b>
 *
 * <h3>Pipeline</h3>
 * <ol>
 *   <li><b>Normalize.</b> Lowercase, strip punctuation, transliterate Bangla
 *       script via {@link QueryExpander#normalizeBengali}.</li>
 *   <li><b>Expand tokens.</b> Pull synonyms (apple↔iphone), brand line aliases
 *       (xiaomi↔redmi), and known misspellings (ipone→iphone). The expanded
 *       set drives every downstream pass.</li>
 *   <li><b>Atlas Search</b> when the env var enables it — Mongo's own fuzzy
 *       text + autocomplete with {@code maxEdits=1}. Fast path.</li>
 *   <li><b>Mongo $text</b> over the {@code name+description} text index, using
 *       the <i>expanded</i> query so apple finds iPhones.</li>
 *   <li><b>OR-regex over expanded tokens</b> — any token may match. Earlier
 *       versions required <i>every</i> token (AND), which is why "Apple 15
 *       Pro" returned nothing. We now collect anything that hits a token
 *       and let the re-ranker sort it out.</li>
 *   <li><b>Trigram fuzzy fallback</b> when we still have fewer than
 *       {@code MIN_RECALL} results. Catches "ipone 15" → "iPhone 15 Pro".</li>
 * </ol>
 *
 * <h3>Ranking</h3>
 * Hybrid score: {@code 0.55·tokenCoverage + 0.25·trigramSim + 0.15·brandHit +
 * 0.05·priceFavor}. Sponsored products keep their organic score for
 * sort/filter consistency, but the controller-facing response also lists
 * their IDs so the UI can render a "Sponsored" chip.
 *
 * <h3>Caching</h3>
 * Results live in Caffeine for 60s, keyed on the normalized query. The
 * 60s TTL is short on purpose — when the nightly indexer re-runs we want
 * cheaper, fresher results visible the next minute.
 */
@Service
public class CatalogSearchService {

    private static final Logger log = LoggerFactory.getLogger(CatalogSearchService.class);
    private static final Pattern NON_ALPHA = Pattern.compile("[^a-z0-9\\s]");

    /** Accessory/peripheral words — products matching these are pushed DOWN when
     *  the query itself isn't for an accessory, so "laptop" surfaces laptops, not
     *  laptop keyboard covers, and "iphone" surfaces phones, not phone cases. */
    private static final Pattern ACCESSORY = Pattern.compile(
            "\\b(case|cover|protector|tempered|glass|skin|pouch|sleeve|holder|stand|mount|film|guard|bumper|casing)\\b");

    /** Below this many regex/text hits, we ask the trigram index for help. */
    private static final int MIN_RECALL = 8;
    /**
     * Trigram Jaccard cutoff. Set low because Jaccard penalises long product
     * names — e.g. "samsoong galxy" vs "Samsung Galaxy S24 Ultra 12/256GB"
     * scores ~0.16 (5/(14+22-5)). Anything visibly junky scores below 0.10.
     */
    private static final double TRIGRAM_MIN = 0.12;
    /** Threshold for surfacing a "did you mean" — needs to be visibly close. */
    private static final double DID_YOU_MEAN_MIN = 0.25;

    private final ProductRepository productRepository;
    private final QueryClassifier classifier;
    private final QueryExpander expander;
    private final TrigramSearchIndex trigram;
    private final AtlasSearchService atlasSearch;

    @Value("${search.page-size:30}")
    private int pageSize;

    public CatalogSearchService(ProductRepository productRepository,
                                QueryClassifier classifier,
                                QueryExpander expander,
                                TrigramSearchIndex trigram,
                                AtlasSearchService atlasSearch) {
        this.productRepository = productRepository;
        this.classifier = classifier;
        this.expander = expander;
        this.trigram = trigram;
        this.atlasSearch = atlasSearch;
    }

    @Cacheable(
        value = "search",
        key = "#query == null ? '' : T(java.util.regex.Pattern).compile('\\\\s+').matcher(#query.trim()).replaceAll(' ').toLowerCase()",
        condition = "#query != null && #query.trim().length() >= 2",
        unless = "#result == null || #result.totalResults == null"
    )
    public SearchResponse search(String query) {
        String bengaliFixed = expander.normalizeBengali(query == null ? "" : query);
        QueryIntent intent = classifier.classify(bengaliFixed);

        List<String> queryTokens = tokens(normalise(bengaliFixed));
        Set<String> expandedTokens = expander.expandTokens(queryTokens);
        Set<String> brandsLower = new java.util.HashSet<>();
        if (intent.getBrands() != null)
            for (String b : intent.getBrands()) if (b != null) brandsLower.add(b.toLowerCase());

        List<Product> raw = new ArrayList<>(textOrRegexSearch(bengaliFixed, expandedTokens));

        // RELEVANCE GATE. textOrRegexSearch is recall-first: OR-regex matches any
        // token as a SUBSTRING, so it drags in products that merely share a
        // letter-run with the query — "air conditiiners" hitting "Airy" earphones,
        // "formal pants" hitting baby "Pants" diapers — and nothing dropped them.
        // Keep a product only if it genuinely matches: a whole-word/synonym hit on
        // a detected brand, or >=60% of the query tokens covered as whole words.
        if (!queryTokens.isEmpty()) {
            raw.removeIf(p -> !isRelevant(p, queryTokens, brandsLower));
        }

        // Trigram fallback when the (now relevance-filtered) literal passes are thin
        String didYouMean = null;
        if (raw.size() < MIN_RECALL && trigram.isEnabled()) {
            List<TrigramIndex.Hit> fuzzy = trigram.topK(bengaliFixed, pageSize, TRIGRAM_MIN);
            if (!fuzzy.isEmpty()) {
                Map<String, Product> have = new LinkedHashMap<>();
                for (Product p : raw) if (p.getId() != null) have.put(p.getId(), p);
                for (TrigramIndex.Hit h : fuzzy) {
                    if (have.containsKey(h.id())) continue;
                    // a fuzzy hit still has to be about the query (synonym/typo aware),
                    // so a no-match query returns nothing instead of trigram noise.
                    if (h.payload() instanceof Product p && isRelevant(p, queryTokens, brandsLower)) {
                        have.put(p.getId(), p);
                    }
                }
                // "did you mean" when nothing matched but the top fuzzy hit is
                // visibly close (real typo) — not for "saaaamssung" with no match.
                if (have.isEmpty()
                        && fuzzy.get(0).score() >= DID_YOU_MEAN_MIN
                        && fuzzy.get(0).payload() instanceof Product top) {
                    didYouMean = top.getName();
                }
                raw = new ArrayList<>(have.values());
            }
        }

        // Hybrid re-rank
        rankInPlace(raw, queryTokens, expandedTokens, intent);

        // Sponsored injection — put one paid product into the top slot when
        // it isn't already in the list and is plausibly relevant. We dedupe
        // by id and surface the chosen IDs separately.
        List<String> sponsoredIds = new ArrayList<>();
        try {
            Product sponsor = pickSponsor(intent);
            if (sponsor != null && sponsor.getId() != null) {
                raw.removeIf(p -> sponsor.getId().equals(p.getId()));
                raw.add(0, sponsor);
                sponsoredIds.add(sponsor.getId());
            }
        } catch (DataAccessException e) {
            log.debug("Sponsored pick failed (ignored): {}", e.getMessage());
        }

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
                .didYouMean(didYouMean)
                .sponsoredProductIds(sponsoredIds)
                .build();
    }

    /**
     * Prefix-style autocomplete, with trigram fuzzy fallback when the
     * prefix returns nothing useful. Same payload shape as before so the
     * existing SearchBar component renders unchanged.
     */
    public List<Map<String, Object>> autocomplete(String prefix, int limit) {
        if (prefix == null || prefix.isBlank()) return List.of();
        String escaped = Pattern.quote(prefix.trim());
        LinkedHashMap<String, Map<String, Object>> dedup = new LinkedHashMap<>();
        try {
            Pageable page = PageRequest.of(0, Math.max(1, Math.min(limit, 20)));
            for (Product p : productRepository.findByNamePrefix(escaped, page)) {
                addSuggestion(dedup, p);
                if (dedup.size() >= limit) break;
            }
        } catch (DataAccessException e) {
            // fall through to trigram
        }

        if (dedup.size() < limit && trigram.isEnabled()) {
            List<TrigramIndex.Hit> fuzzy = trigram.topK(prefix, limit * 2, TRIGRAM_MIN);
            for (TrigramIndex.Hit h : fuzzy) {
                if (h.payload() instanceof Product p) {
                    addSuggestion(dedup, p);
                    if (dedup.size() >= limit) break;
                }
            }
        }
        return new ArrayList<>(dedup.values());
    }

    private static void addSuggestion(LinkedHashMap<String, Map<String, Object>> dedup, Product p) {
        if (p == null || p.getName() == null) return;
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
    }

    private List<Product> textOrRegexSearch(String query, Set<String> expandedTokens) {
        if (query == null || query.isBlank()) return List.of();
        Pageable page = PageRequest.of(0, pageSize);
        Map<String, Product> merged = new LinkedHashMap<>();

        // Pass 0: Atlas Search (fuzzy + autocomplete) — fast path on paid clusters
        if (atlasSearch.isEnabled()) {
            List<Product> atlasHits = atlasSearch.search(query, pageSize);
            if (atlasHits != null) {
                for (Product p : atlasHits) {
                    if (p.getId() != null) merged.putIfAbsent(p.getId(), p);
                }
                if (merged.size() >= pageSize) return new ArrayList<>(merged.values());
            }
        }

        // Pass 1: Mongo $text — try expanded query first, then raw if expansion was a no-op
        String expandedQuery = expandedTokens.isEmpty() ? query : String.join(" ", expandedTokens);
        try {
            for (Product p : productRepository.textSearch(expandedQuery, page)) {
                if (p.getId() != null) merged.putIfAbsent(p.getId(), p);
            }
        } catch (Exception e) {
            log.debug("Mongo $text search failed ({}), continuing", e.getMessage());
        }

        // Pass 2: OR-regex over expanded tokens. Earlier behaviour was AND;
        // we relax to OR so any token can match, then rely on rankInPlace
        // to surface the products that match the most tokens first.
        if (!expandedTokens.isEmpty() && merged.size() < pageSize) {
            try {
                for (Product p : productRepository.findByNamePrefix(buildOrRegex(expandedTokens), page)) {
                    if (p.getId() != null) merged.putIfAbsent(p.getId(), p);
                }
            } catch (Exception e) {
                log.debug("OR-regex pass failed: {}", e.getMessage());
            }
        }

        // Pass 3: raw substring — last-ditch contiguous-phrase match
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

    private Product pickSponsor(QueryIntent intent) {
        Pageable one = PageRequest.of(0, 1);
        LocalDateTime now = LocalDateTime.now();
        String cat = intent.primaryCategory().getLabel();
        if (cat != null && !cat.isBlank()) {
            List<Product> byCat = productRepository.findActiveSponsoredByCategory(now, cat, one);
            if (!byCat.isEmpty()) return byCat.get(0);
        }
        List<Product> any = productRepository.findActiveSponsored(now, one);
        return any.isEmpty() ? null : any.get(0);
    }

    private void rankInPlace(List<Product> products,
                             List<String> queryTokens,
                             Set<String> expandedTokens,
                             QueryIntent intent) {
        if (products.isEmpty()) return;
        Set<String> brandsLower = new java.util.HashSet<>();
        if (intent.getBrands() != null) {
            for (String b : intent.getBrands()) if (b != null) brandsLower.add(b.toLowerCase());
        }
        // Detected category (e.g. "Laptops") boosts in-category products so
        // "laptop" surfaces actual laptops (named by model, no "laptop" word)
        // over laptop accessories. "General" is too broad to boost on.
        String catLower = intent.primaryCategory() == null || intent.primaryCategory().getLabel() == null
                ? null : intent.primaryCategory().getLabel().toLowerCase();
        if ("general".equals(catLower)) catLower = null;
        final String cat = catLower;
        products.sort(Comparator.comparingDouble((Product p) ->
                -hybridScore(p, queryTokens, expandedTokens, brandsLower, cat)));
    }

    private static double hybridScore(Product p, List<String> queryTokens,
                                      Set<String> expandedTokens, Set<String> brands, String catLower) {
        if (p == null || p.getName() == null) return 0;
        String lname = p.getName().toLowerCase();
        double tokenCov = coverage(lname, queryTokens);
        double expandedCov = coverage(lname, new ArrayList<>(expandedTokens));
        double brandHit = 0;
        for (String b : brands) {
            if (lname.contains(b)) { brandHit = 1; break; }
        }
        double priceFavor = (p.getLowestPrice() == null || p.getLowestPrice() <= 0) ? 0
                : 1.0 / (1.0 + Math.log10(p.getLowestPrice()));
        // Push accessories below the real product when the user didn't ask for one.
        double accessoryPenalty = 0;
        if (ACCESSORY.matcher(lname).find()
                && !ACCESSORY.matcher(String.join(" ", queryTokens)).find()) {
            accessoryPenalty = 0.30;
        }
        double catBoost = (catLower != null && p.getCategory() != null
                && catLower.equals(p.getCategory().toLowerCase())) ? 0.25 : 0;
        return 0.55 * tokenCov + 0.25 * expandedCov + 0.15 * brandHit + 0.05 * priceFavor
                - accessoryPenalty + catBoost;
    }

    private static double coverage(String name, List<String> tokens) {
        if (name == null || tokens.isEmpty()) return 0;
        long matched = tokens.stream().filter(t -> t != null && !t.isBlank() && name.contains(t)).count();
        return (double) matched / tokens.size();
    }

    /** Genuine relevance gate (vs the recall-first substring passes): keep a
     *  product only if a detected brand word-matches the name, or the query's
     *  tokens are mostly covered as WHOLE WORDS (synonym/typo aware). This stops
     *  a single loose substring hit qualifying — "air"→"Airy" earphone,
     *  "pants"→baby "Pants" diaper. */
    private boolean isRelevant(Product p, List<String> queryTokens, Set<String> brandsLower) {
        if (p == null || p.getName() == null) return false;
        if (queryTokens.isEmpty()) return true;
        String name = " " + p.getName().toLowerCase() + " ";
        // a detected brand appearing as a whole word is the strongest single signal
        for (String b : brandsLower) if (b.length() >= 2 && wordMatch(name, b)) return true;
        int covered = 0;
        for (String t : queryTokens) if (tokenHits(name, t)) covered++;
        double cov = (double) covered / queryTokens.size();
        // 1-token query: that token must hit. Multi-token: need >=60% coverage,
        // so a 2-word query can't pass on one generic word alone.
        return queryTokens.size() == 1 ? covered >= 1 : cov >= 0.6;
    }

    /** Whole-word hit for a token or any of its known synonyms/misspellings
     *  (so apple↔iphone, xiaomi↔redmi, ipone→iphone still count). */
    private boolean tokenHits(String paddedName, String token) {
        if (token == null || token.length() < 2) return false;
        if (wordMatch(paddedName, token)) return true;
        for (String v : expander.expandTokens(java.util.List.of(token))) {
            if (v != null && v.length() >= 2 && !v.equals(token) && wordMatch(paddedName, v)) return true;
        }
        return false;
    }

    /** True if {@code token} appears as a whole word in the (space-padded,
     *  lowercased) name. Word-boundary: "air" does NOT match "airy", "pro" does
     *  NOT match "product". */
    private static boolean wordMatch(String paddedLowerName, String token) {
        try {
            return Pattern.compile("\\b" + Pattern.quote(token) + "\\b").matcher(paddedLowerName).find();
        } catch (RuntimeException e) {
            return paddedLowerName.contains(" " + token + " ");
        }
    }

    /** Build an OR regex: any of the tokens may appear, anywhere. */
    private static String buildOrRegex(Set<String> tokens) {
        if (tokens.isEmpty()) return ".+";
        StringBuilder sb = new StringBuilder("(");
        boolean first = true;
        for (String t : tokens) {
            if (t == null || t.isBlank()) continue;
            if (!first) sb.append("|");
            sb.append(Pattern.quote(t));
            first = false;
        }
        sb.append(")");
        return sb.toString();
    }

    private static String normalise(String s) {
        if (s == null) return "";
        return NON_ALPHA.matcher(s.toLowerCase()).replaceAll(" ").replaceAll("\\s+", " ").trim();
    }

    private static List<String> tokens(String s) {
        if (s == null || s.isBlank()) return List.of();
        // Keep model-number tokens like "S24" / "M3" by lowering the length floor.
        return java.util.Arrays.stream(s.split("\\s+")).filter(t -> t.length() >= 2).toList();
    }
}
