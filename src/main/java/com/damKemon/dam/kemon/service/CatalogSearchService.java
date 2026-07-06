package com.damKemon.dam.kemon.service;

import com.damKemon.dam.kemon.dto.SearchResponse;
import com.damKemon.dam.kemon.intelligence.QueryClassifier;
import com.damKemon.dam.kemon.intelligence.QueryExpander;
import com.damKemon.dam.kemon.intelligence.QueryIntent;
import com.damKemon.dam.kemon.intelligence.SpecExtractor;
import com.damKemon.dam.kemon.intelligence.Stemmer;
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
 *   <li><b>Recall</b> = the in-memory trigram index (a hit qualifies on Jaccard
 *       {@code >= TRIGRAM_MIN} <i>or</i> query-coverage {@code >= COVER_MIN}) plus
 *       Mongo {@code $text} over {@code name+description} using the <i>expanded</i>
 *       query. Coverage is length-independent, so a short typo ("labtop") whose
 *       Jaccard is tiny against a long name still enters the pool.</li>
 *   <li><b>Relevance gate</b> — keep a candidate that genuinely matches
 *       ({@link #isRelevant}: category / whole-word brand / &ge;60% token coverage)
 *       OR whose name contains a token within a small edit distance of the query's
 *       distinctive word ({@link #nameHasFuzzyToken}). That fuzzy path is what
 *       rescues un-dictionaried typos — "oramio"→"oraimo", "samsoong"→"samsung" —
 *       that recall found but the whole-word gate used to discard, silently
 *       returning zero while /suggest showed the product.</li>
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
            "\\b(case|cover|protector|tempered|glass|skin|pouch|sleeve|holder|stand|mount|film|guard|bumper|casing"
          + "|bundle|pack|sticker|lens|charger|cable|adapter|strap|lanyard|grip|stylus|defender|screenguard)\\b");

    /** Upper-bound price phrases — deliberately NO bare "max" (collides with "Pro Max"). */
    private static final Pattern PRICE_MAX = Pattern.compile(
            "\\b(?:under|below|within|upto|up to|less than|at most|cheaper than|maximum of)\\s*"
          + "(?:৳|tk\\.?|bdt|rs\\.?)?\\s*([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*(k|hajar|thousand|lakh|lac|cr|crore)?",
            Pattern.CASE_INSENSITIVE);
    /** Lower-bound price phrases. */
    private static final Pattern PRICE_MIN = Pattern.compile(
            "\\b(?:over|above|more than|at least|starting from|starting at|minimum of)\\s*"
          + "(?:৳|tk\\.?|bdt|rs\\.?)?\\s*([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*(k|hajar|thousand|lakh|lac|cr|crore)?",
            Pattern.CASE_INSENSITIVE);
    /** "cheap/budget" — a soft preference (priceFavor already biases low), not a hard filter; just stripped. */
    private static final Pattern PRICE_CHEAP = Pattern.compile(
            "\\b(cheap|cheapest|budget|affordable|low[\\s-]?price|low[\\s-]?cost)\\b", Pattern.CASE_INSENSITIVE);

    /** Colour/finish words that often appear in a pasted product name ("...Natural
     *  Titanium") but say nothing about product identity. Excluded from the
     *  distinctive-token recall keep so "iphone 17 pro max natural titanium" broadens
     *  on "iphone", never on the colour — keeping the keep precise. */
    private static final Set<String> COLOUR_WORDS = Set.of(
            "titanium","black","white","silver","gold","blue","green","graphite","onyx",
            "natural","desert","midnight","grey","gray","purple","pink","yellow","orange");

    /** Below this many regex/text hits, we ask the trigram index for help. */
    private static final int MIN_RECALL = 8;
    /**
     * Trigram Jaccard cutoff. Set low because Jaccard penalises long product
     * names — e.g. "samsoong galxy" vs "Samsung Galaxy S24 Ultra 12/256GB"
     * scores ~0.16 (5/(14+22-5)). Anything visibly junky scores below 0.10.
     */
    private static final double TRIGRAM_MIN = 0.12;
    /**
     * Query-coverage floor: the fraction of the QUERY's trigrams that must appear in
     * a product name for a fuzzy (typo) match to enter the candidate pool. This is
     * length-independent, unlike the Jaccard {@link #TRIGRAM_MIN} which collapses on
     * long BD product names — a correct "oramio cord flex" → "Oraimo CordForce …
     * Vacuum" match is only ~0.14 Jaccard but ~0.56 coverage. Calibrated at 0.45:
     * real typos score 0.50–0.90, noise ≤0.25. Recall admits on coverage; the gate
     * then keeps only those whose distinctive word actually fuzzy-matches the name
     * ({@link #nameHasFuzzyToken}), so high coverage from shared generic tokens
     * ("…15 pro") can't drag in the wrong brand.
     */
    private static final double COVER_MIN = 0.45;

    private final ProductRepository productRepository;
    private final QueryClassifier classifier;
    private final QueryExpander expander;
    private final TrigramSearchIndex trigram;
    private final AtlasSearchService atlasSearch;
    private final ShopVisibilityService shopVisibility;

    /** Default page size when the caller doesn't specify one. */
    @Value("${search.page-size:30}")
    private int pageSize;

    /** Upper bound on a single requested page size (guards against abuse). */
    @Value("${search.max-page-size:60}")
    private int maxPageSize;

    /**
     * How many candidates we gather + rank before paginating. The response is
     * sliced from this ranked pool, so it bounds how deep "Load more" can go in
     * one query. 300 keeps ranking in-memory cheap while covering broad browses
     * (e.g. "laptop") far past the old 30-result wall.
     */
    @Value("${search.max-candidates:300}")
    private int maxCandidates;

    public CatalogSearchService(ProductRepository productRepository,
                                QueryClassifier classifier,
                                QueryExpander expander,
                                TrigramSearchIndex trigram,
                                AtlasSearchService atlasSearch,
                                ShopVisibilityService shopVisibility) {
        this.productRepository = productRepository;
        this.classifier = classifier;
        this.expander = expander;
        this.trigram = trigram;
        this.atlasSearch = atlasSearch;
        this.shopVisibility = shopVisibility;
    }

    /** Back-compat entry point: first page at the default size. */
    public SearchResponse search(String query) {
        return search(query, 0, pageSize, false, java.util.Map.of());
    }

    /** Back-compat entry point: accessories hidden by default on device queries. */
    public SearchResponse search(String query, int page, int size) {
        return search(query, page, size, false, java.util.Map.of());
    }

    /** Back-compat entry point without spec/variant filters. */
    public SearchResponse search(String query, int page, int size, boolean includeAccessories) {
        return search(query, page, size, includeAccessories, java.util.Map.of());
    }

    /**
     * Paginated search. We gather + rank up to {@code maxCandidates} once, then
     * return the requested page slice with {@code hasMore} so the UI can offer
     * "Load more" — replacing the old hard 30-result wall. Cached per
     * (normalized query, page, size, includeAccessories) for 60s.
     *
     * <p>{@code includeAccessories} re-admits cases/covers/protectors on a device
     * query (the UI's "Show accessories" toggle); by default they're hidden so a
     * "phone" / "iphone 15" search surfaces phones, not phone cases.
     */
    @Cacheable(
        value = "search",
        key = "(#query == null ? '' : T(java.util.regex.Pattern).compile('\\\\s+').matcher(#query.trim()).replaceAll(' ').toLowerCase()) + '|' + #page + '|' + #size + '|' + #includeAccessories + '|' + (#specFilters == null ? '' : #specFilters)",
        condition = "#query != null && #query.trim().length() >= 2",
        unless = "#result == null || #result.totalResults == null"
    )
    public SearchResponse search(String query, int page, int size, boolean includeAccessories,
                                 Map<String, String> specFilters) {
        final int pageIdx = Math.max(0, page);
        final int pageLen = size <= 0 ? pageSize : Math.min(size, maxPageSize);
        String bengaliFixed = expander.normalizeBengali(query == null ? "" : query);
        // Canonicalise so different spellings of the SAME query converge: collapse
        // known spaced phrases ("play station"→"playstation") then split glued
        // letter/digit runs ("iphone17"→"iphone 17", "playstation5"→"playstation 5")
        // so "iphone17" and "iphone 17" return identical results. Applied before
        // classification + tokenisation so every downstream pass sees one form.
        // Short model codes (ps5/s24/m3/g923) stay glued — products name them that way.
        bengaliFixed = splitAlphaNum(expander.collapsePhrases(bengaliFixed));

        // Price-intent: lift "under 20000 / below 50k / over 1 lakh / cheap" out of
        // the query, search the remaining product words, then filter the results by
        // lowest price. Natural for a price-comparison site ("phone under 20000").
        PriceFilter pf = parsePriceIntent(bengaliFixed);
        bengaliFixed = pf.cleanedQuery();

        // Singularize the query so the boundary-aware classifier + keyword match
        // fire on plurals too — "smart watches"/"smartwatches" now classify the
        // same as "smart watch"/"smartwatch". See Stemmer + AhoCorasick.
        String stemmed = Stemmer.singularizePhrase(bengaliFixed);
        QueryIntent intent = classifier.classify(stemmed);

        List<String> queryTokens = tokens(normalise(stemmed));
        Set<String> expandedTokens = expander.expandTokens(queryTokens);
        Set<String> brandsLower = new java.util.HashSet<>();
        if (intent.getBrands() != null)
            for (String b : intent.getBrands()) if (b != null) brandsLower.add(b.toLowerCase());

        // CATEGORY-BROWSE: a bare category term ("laptop", "gaming laptop") with no
        // brand/model. Real products are named by model ("ASUS VivoBook"), so they
        // never name-match — pull them straight from the detected category so the
        // results are actual laptops, not just things with "laptop" in the name.
        String catLabel = (intent.primaryCategory() == null) ? null : intent.primaryCategory().getLabel();
        boolean categoryBrowse = isCategoryBrowse(catLabel, queryTokens, brandsLower);
        String browseCat = categoryBrowse ? catLabel.toLowerCase() : null;

        // One fuzzy pass over the trigram index, reused for recall and the relevance
        // gate below. topK retains matches by max(Jaccard, coverage), so a correct typo
        // match on a long name is present here even though its Jaccard is tiny.
        List<TrigramIndex.Hit> fuzzy = trigram.isEnabled()
                ? trigram.topK(bengaliFixed, maxCandidates, 0.0) : List.of();

        List<Product> atlasHits = null;
        if (atlasSearch.isEnabled()) {
            atlasHits = atlasSearch.search(bengaliFixed, maxCandidates);
        }
        boolean atlasSucceeded = (atlasHits != null);

        Map<String, Product> bag = new LinkedHashMap<>();
        if (atlasSucceeded) {
            for (Product p : atlasHits) if (p.getId() != null) bag.put(p.getId(), p);
        } else {
            for (Product p : textOrRegexSearch(bengaliFixed, expandedTokens, fuzzy))
                if (p.getId() != null) bag.put(p.getId(), p);
        }
        
        if (categoryBrowse) {
            try {
                // Indexed exact match on the lower-cased label (categories are
                // stored lower-case). findByCategoryIgnoreCase was a full-catalog
                // regex scan — the dominant cost of a cold "laptop"/"phone" browse.
                for (Product p : productRepository
                        .findByCategory(catLabel.toLowerCase(), PageRequest.of(0, maxCandidates)).getContent())
                    if (p.getId() != null) bag.putIfAbsent(p.getId(), p);
            } catch (DataAccessException e) {
                log.debug("Category-browse fetch failed: {}", e.getMessage());
            }
        }
        // Blocked/hidden shops: drop their offers (and fully-hidden products)
        // BEFORE gating/faceting/ranking, so counts, facets and sitesSearched
        // all reflect what the shopper can actually see.
        List<Product> candidates = shopVisibility.filterForPublic(new ArrayList<>(bag.values()));
        List<Product> raw = new ArrayList<>(candidates);

        // RELEVANCE GATE. Recall is fuzzy (trigram) but this gate used to be
        // whole-word-EXACT, so it discarded the very typo matches recall found:
        // "oramio"≠"oraimo", "labtop"≠"laptop". Keep a product when it genuinely
        // matches (isRelevant: browsed category, whole-word brand hit, or >=60% of
        // the query tokens covered as whole words) OR when its name contains a token
        // within a small edit distance of the query's distinctive word — the fuzzy
        // path that rescues un-dictionaried brand/model typos. keptByFuzzyOnly records
        // the pure typo corrections so a Google-style "did you mean" can point at the
        // top one. Precision holds: isRelevant is unchanged, and the fuzzy path keys
        // on the DISTINCTIVE word (so "formal pants" still can't match a baby "Pants"
        // diaper — "formal" fuzzy-matches nothing there).
        String distinctiveTok = distinctiveToken(queryTokens);
        java.util.Set<String> keptByFuzzyOnly = new java.util.HashSet<>();
        if (!queryTokens.isEmpty()) {
            List<Product> kept = new ArrayList<>(raw.size());
            for (Product p : raw) {
                if (isRelevant(p, queryTokens, brandsLower, browseCat)) {
                    kept.add(p);
                } else if (distinctiveTok != null && nameHasFuzzyToken(p, distinctiveTok)) {
                    kept.add(p);
                    if (p.getId() != null) keptByFuzzyOnly.add(p.getId());
                }
            }
            raw = kept;
        }

        // GRACEFUL RECALL. A brand/model query whose exact model isn't stocked
        // ("iphone 17" when we only carry iPhone 16 + a few iPhone-17 accessories)
        // shouldn't collapse to accessories-only or nothing. When the strict gate
        // leaves us thin, re-admit candidates that share the query's most
        // distinctive word (e.g. "iphone", "samsung", "macbook") so the shopper
        // still sees the right family of products. They rank BELOW exact matches
        // via the hybrid score, and the distinctive word excludes unrelated noise
        // (a baby "Pants" diaper never carries "formal", so "formal pants" stays
        // clean). Generalises to "samsung s30", "macbook m5", "pixel 12", etc.
        // GRACEFUL RECALL.
        if (raw.size() < MIN_RECALL && !queryTokens.isEmpty()) {
            String key = distinctiveToken(queryTokens);
            if (key != null) {
                Set<String> have = new java.util.HashSet<>();
                for (Product p : raw) if (p.getId() != null) have.add(p.getId());
                for (Product p : candidates) {
                    if (p.getId() == null || have.contains(p.getId()) || p.getName() == null) continue;
                    if (tokenHits(" " + p.getName().toLowerCase() + " ", key)) {
                        raw.add(p);
                        have.add(p.getId());
                    }
                }
            }
        }

        // Did-you-mean is set AFTER ranking (below). The old trigram-fallback block
        // that stood here — re-running topK and rescuing hits only above a 0.25
        // Jaccard cliff — is gone: the relevance gate above now keeps typo matches
        // directly (query-coverage recall + distinctive-word fuzzing), so a correct
        // match on a long BD name no longer has to clear a threshold it never could.
        String didYouMean = null;

        // HARD accessory exclusion (item 2). When the shopper is clearly after a
        // DEVICE (a device category or a brand/model query, not an accessory
        // query), DROP accessory products instead of merely demoting them — so
        // "phone" / "iphone 15" surface phones, not cases & covers. Never empties
        // the page: if only accessories matched, keep them. The UI's "Show
        // accessories" toggle re-includes them via includeAccessories.
        if (!includeAccessories && isDeviceIntent(intent)
                && !isAccessoryQuery(intent, queryTokens) && !raw.isEmpty()) {
            List<Product> nonAcc = new ArrayList<>();
            for (Product p : raw) if (!isAccessoryProduct(p)) nonAcc.add(p);
            if (!nonAcc.isEmpty()) raw = nonAcc;
        }

        // Variant spec facets + filter (item 3). Facets are computed over the
        // relevance-gated matches BEFORE narrowing, so the shopper always sees the
        // full set of RAM/Storage/Display options; the selected specs then narrow
        // the results. Specs are parsed from product names — phones/computing first.
        Map<String, List<Map<String, Object>>> facets = computeFacets(raw);
        if (specFilters != null && !specFilters.isEmpty()) {
            raw.removeIf(p -> !matchesSpecs(p, specFilters));
        }

        // Price-intent filter: drop products whose lowest price falls outside the
        // requested range (a no-price product can't satisfy a price constraint).
        if (pf.hasConstraint()) {
            raw.removeIf(p -> !pf.matches(p.getLowestPrice()));
        }

        // Hybrid re-rank
        rankInPlace(raw, queryTokens, expandedTokens, intent, browseCat);

        // Did-you-mean: if the top result is a fuzzy typo correction (kept only
        // because its name fuzzy-matches the distinctive query word, not an exact
        // match), surface it like Google's "showing results for". Only when it's the
        // TOP result — if exact matches outrank it, no correction is needed.
        if (!raw.isEmpty() && raw.get(0).getId() != null
                && keptByFuzzyOnly.contains(raw.get(0).getId())) {
            didYouMean = raw.get(0).getName();
        }

        // Sponsored injection — put one paid product into the top slot when
        // it isn't already in the list, is plausibly relevant, and (if a price
        // range was asked for) fits it. Only on the first page (it's the top
        // slot, not something that should reappear as the user pages down).
        List<String> sponsoredIds = new ArrayList<>();
        if (pageIdx == 0) {
            try {
                Product sponsor = pickSponsor(intent);
                if (sponsor != null && sponsor.getId() != null && pf.matches(sponsor.getLowestPrice())) {
                    raw.removeIf(p -> sponsor.getId().equals(p.getId()));
                    raw.add(0, sponsor);
                    sponsoredIds.add(sponsor.getId());
                }
            } catch (DataAccessException e) {
                log.debug("Sponsored pick failed (ignored): {}", e.getMessage());
            }
        }

        // Cap the ranked pool, then page over it. totalResults is the FULL ranked
        // count (so the UI knows the real size); products is just this page's slice
        // and hasMore drives "Load more".
        if (raw.size() > maxCandidates) raw = new ArrayList<>(raw.subList(0, maxCandidates));
        int total = raw.size();
        int from = Math.min(pageIdx * pageLen, total);
        int to = Math.min(from + pageLen, total);
        List<Product> pageItems = new ArrayList<>(raw.subList(from, to));
        boolean hasMore = to < total;

        List<String> sitesSet = pageItems.stream()
                .flatMap(p -> p.getPrices() == null ? java.util.stream.Stream.empty() : p.getPrices().stream())
                .map(sp -> sp.getSiteName()).filter(java.util.Objects::nonNull).distinct().toList();

        return SearchResponse.builder()
                .query(query)
                .products(pageItems)
                .totalResults(total)
                .page(pageIdx)
                .size(pageLen)
                .hasMore(hasMore)
                .sitesSearched(new ArrayList<>(sitesSet))
                .sitesSkipped(List.of())
                .detectedCategory(intent.primaryCategory().getLabel())
                .categories(intent.getCategories().stream().map(c -> c.getLabel()).collect(Collectors.toList()))
                .brands(intent.getBrands())
                .confidence(intent.getConfidence())
                .didYouMean(didYouMean)
                .sponsoredProductIds(sponsoredIds)
                .facets(facets)
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
                if (shopVisibility.fullyHidden(p)) continue;
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
                    if (shopVisibility.fullyHidden(p)) continue;
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

    private List<Product> textOrRegexSearch(String query, Set<String> expandedTokens,
                                            List<TrigramIndex.Hit> fuzzy) {
        if (query == null || query.isBlank()) return List.of();
        // Gather a deep candidate pool (not just one page) so ranking + pagination
        // have something to work with — this is what lets "Load more" go past 30.
        Pageable page = PageRequest.of(0, maxCandidates);
        Map<String, Product> merged = new LinkedHashMap<>();

        // Recall = the same fast engine /suggest uses: the in-memory trigram index
        // (fuzzy + typo-tolerant, "oramio" -> "Oraimo") plus Mongo's indexed $text. We
        // DROPPED the unanchored-regex collection scans (OR-regex, contains, and the
        // AND-lookahead pass) that scanned the whole catalog on EVERY query and were
        // spiking CPU and crashing the web JVM. Both remaining sources are cheap.
        // A trigram hit qualifies on EITHER a Jaccard score >= TRIGRAM_MIN (the
        // original signal) OR query-coverage >= COVER_MIN — the length-independent
        // signal that lets a short typo like "labtop" pull in "…VivoBook…Laptop",
        // whose Jaccard is far below TRIGRAM_MIN. The gate downstream then keeps only
        // the ones whose distinctive word truly fuzzy-matches, so this stays precise.
        for (TrigramIndex.Hit h : fuzzy) {
            if ((h.score() >= TRIGRAM_MIN || h.coverage() >= COVER_MIN)
                    && h.payload() instanceof Product p && p.getId() != null) {
                merged.putIfAbsent(p.getId(), p);
            }
        }

        // Mongo $text (uses the name/description text index — NOT a collection scan).
        String expandedQuery = expandedTokens.isEmpty() ? query : String.join(" ", expandedTokens);
        try {
            for (Product p : productRepository.textSearch(expandedQuery, page)) {
                if (p.getId() != null) merged.putIfAbsent(p.getId(), p);
            }
        } catch (Exception e) {
            log.debug("Mongo $text search failed ({}), continuing", e.getMessage());
        }

        // REMOVED: the OR-regex-over-expanded-tokens pass and the raw
        // findByNameContainingIgnoreCase pass. Both are unanchored regex/contains
        // scans — Mongo cannot use the name index for either, so each one walked
        // the FULL catalog on every request (the contains-scan had no page limit
        // at all). Stacked together (plus the AND-lookahead pass added and removed
        // in the same incident) they were the CPU spikes that crashed the web JVM.
        // Recall is trigram + $text only now, matching what /suggest already proved
        // fast and accurate in prod.
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
                             QueryIntent intent,
                             String browseCat) {
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
        if (browseCat != null) {
            // Category browse ("laptop", "phone under 20k"): products that ARE in
            // the browsed category rank ahead of mere name-matches (e.g. an
            // earphone named "...Phone...") — then by hybrid score within each tier.
            products.sort(Comparator
                    .comparingInt((Product p) -> inCategory(p, browseCat) ? 0 : 1)
                    .thenComparingDouble(p -> -hybridScore(p, queryTokens, expandedTokens, brandsLower, cat)));
        } else {
            products.sort(Comparator.comparingDouble((Product p) ->
                    -hybridScore(p, queryTokens, expandedTokens, brandsLower, cat)));
        }
    }

    private static boolean inCategory(Product p, String catLower) {
        return catLower != null && p.getCategory() != null && catLower.equals(p.getCategory().toLowerCase());
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
        // Popularity: multi-seller products are the ones people actually buy — this
        // orders a category browse by real products, not the cheapest oddity.
        int sellers = p.getPrices() == null ? 0 : p.getPrices().size();
        double popular = Math.min(sellers, 5) / 5.0;
        return 0.55 * tokenCov + 0.25 * expandedCov + 0.15 * brandHit + 0.05 * priceFavor
                - accessoryPenalty + catBoost + 0.06 * popular;
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
    boolean isRelevant(Product p, List<String> queryTokens, Set<String> brandsLower, String browseCat) {
        if (p == null || p.getName() == null) return false;
        if (queryTokens.isEmpty()) return true;
        // category-browse: any product in the detected category qualifies — real
        // laptops ("ASUS VivoBook") don't carry the word "laptop" so token matching
        // alone can never surface them.
        if (browseCat != null && p.getCategory() != null && browseCat.equals(p.getCategory().toLowerCase()))
            return true;
        String name = " " + p.getName().toLowerCase() + " ";
        // a detected brand appearing as a whole word is the strongest single signal
        for (String b : brandsLower) if (b.length() >= 2 && wordMatch(name, b)) return true;
        // RECALL KEEP: never drop a product that carries the query's most distinctive
        // (brand/family) word. "iphone 17 pro max natural titanium" must still match the
        // catalog's shorter "Apple iPhone 17 Pro Max" even though the colour/spec tokens
        // miss — that 4/7 coverage was falling under the 60% gate and the real, in-stock
        // product vanished. Colours are excluded from "distinctive" so this can't broaden
        // to noise (a baby "Pants" diaper still fails "formal pants"); exact matches still
        // outrank this via the hybrid score. Generalises to samsung/macbook/pixel/redmi…
        String distinctive = distinctiveToken(queryTokens);
        if (distinctive != null && tokenHits(name, distinctive)) return true;
        int covered = 0;
        for (String t : queryTokens) if (tokenHits(name, t)) covered++;
        double cov = (double) covered / queryTokens.size();
        // 1-token query: that token must hit. Multi-token: need >=60% coverage,
        // so a 2-word query can't pass on one generic word alone.
        return queryTokens.size() == 1 ? covered >= 1 : cov >= 0.6;
    }

    /** A bare category query ("laptop", "gaming laptop"): a specific category was
     *  detected and there's no brand or model number that would make it a search
     *  for a particular product (where name-matching already works). */
    private static boolean isCategoryBrowse(String catLabel, List<String> queryTokens, Set<String> brandsLower) {
        if (catLabel == null || catLabel.isBlank() || catLabel.equalsIgnoreCase("General")) return false;
        if (queryTokens.isEmpty() || !brandsLower.isEmpty()) return false;
        for (String t : queryTokens) if (t.matches(".*\\d.*")) return false;   // model number → specific
        return true;
    }

    /** True when the shopper is clearly after a DEVICE (a specific non-accessory,
     *  non-general category was detected) — the case where stray accessory matches
     *  should be hidden rather than just demoted. */
    private static boolean isDeviceIntent(QueryIntent intent) {
        if (intent == null || intent.primaryCategory() == null) return false;
        String n = intent.primaryCategory().name();
        return !"ACCESSORY".equals(n) && !"GENERAL".equals(n);
    }

    /** True when the query itself is FOR an accessory — so we must NOT hide them
     *  ("phone case", "screen protector", "charger" stay visible). */
    private static boolean isAccessoryQuery(QueryIntent intent, List<String> queryTokens) {
        if (intent != null && intent.primaryCategory() != null
                && "ACCESSORY".equals(intent.primaryCategory().name())) return true;
        return ACCESSORY.matcher(String.join(" ", queryTokens)).find();
    }

    /** A product that is itself an accessory — by an accessory noun in its name
     *  (case/cover/protector/glass…) or an accessory category. */
    static boolean isAccessoryProduct(Product p) {
        if (p == null || p.getName() == null) return false;
        if (ACCESSORY.matcher(p.getName().toLowerCase()).find()) return true;
        String c = p.getCategory();
        return c != null && c.toLowerCase().contains("accessor");
    }

    // ── price-intent ──────────────────────────────────────────────────────────
    /** A parsed price constraint plus the query with the price phrase removed. */
    private record PriceFilter(Double min, Double max, String cleanedQuery) {
        boolean hasConstraint() { return min != null || max != null; }
        boolean matches(Double price) {
            if (min == null && max == null) return true;
            if (price == null || price <= 0) return false;        // no price can't satisfy a constraint
            if (min != null && price < min) return false;
            if (max != null && price > max) return false;
            return true;
        }
    }

    /** Pull "under 20000 / below 50k / over 1 lakh / cheap" out of the query and
     *  return the bounds + the product-only remainder. "20k"→20000, "1.5 lakh"→150000. */
    private static PriceFilter parsePriceIntent(String q) {
        if (q == null || q.isBlank()) return new PriceFilter(null, null, q);
        String s = q;
        Double max = null, min = null;
        java.util.regex.Matcher mx = PRICE_MAX.matcher(s);
        if (mx.find()) { max = parseAmount(mx.group(1), mx.group(2)); s = s.substring(0, mx.start()) + " " + s.substring(mx.end()); }
        java.util.regex.Matcher mn = PRICE_MIN.matcher(s);
        if (mn.find()) { min = parseAmount(mn.group(1), mn.group(2)); s = s.substring(0, mn.start()) + " " + s.substring(mn.end()); }
        s = PRICE_CHEAP.matcher(s).replaceAll(" ").replaceAll("\\s+", " ").trim();
        return new PriceFilter(min, max, s.isBlank() ? q : s);
    }

    private static Double parseAmount(String num, String suffix) {
        if (num == null) return null;
        try {
            double v = Double.parseDouble(num.replace(",", ""));
            if (suffix != null && !suffix.isBlank()) {
                String sf = suffix.toLowerCase();
                if (sf.startsWith("k") || sf.contains("hajar") || sf.contains("thousand")) v *= 1_000;
                else if (sf.contains("lakh") || sf.contains("lac")) v *= 100_000;
                else if (sf.contains("cr")) v *= 10_000_000;
            }
            return v <= 0 ? null : v;
        } catch (NumberFormatException e) { return null; }
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

    private static String normalise(String s) {
        if (s == null) return "";
        return NON_ALPHA.matcher(s.toLowerCase()).replaceAll(" ").replaceAll("\\s+", " ").trim();
    }

    /**
     * Insert a space between a maximal letter-run and an adjacent digit-run, but
     * only when the LETTER run is &ge;3 chars. So "iphone17"→"iphone 17",
     * "playstation5"→"playstation 5", "windows11"→"windows 11", while short model
     * codes the catalog writes glued stay intact: "ps5", "s24", "m3", "a54",
     * "g923", "5g", "256gb", "i7". This is what makes "iphone17" and "iphone 17"
     * tokenise — and therefore classify, match and rank — identically.
     */
    static String splitAlphaNum(String s) {
        if (s == null || s.length() < 2) return s;
        StringBuilder out = new StringBuilder(s.length() + 4);
        int n = s.length(), i = 0, prevKind = 0, prevAlphaLen = 0;
        while (i < n) {
            int kind = kindOf(s.charAt(i));
            int j = i + 1;
            while (j < n && kindOf(s.charAt(j)) == kind) j++;
            int runLen = j - i;
            boolean boundary = (prevKind == 1 && kind == 2) || (prevKind == 2 && kind == 1);
            if (boundary) {
                int alphaLen = (kind == 1) ? runLen : prevAlphaLen;   // length of the LETTER run at this boundary
                if (alphaLen >= 3) out.append(' ');
            }
            out.append(s, i, j);
            prevKind = kind;
            prevAlphaLen = (kind == 1) ? runLen : 0;
            i = j;
        }
        return out.toString();
    }

    /** 1 = letter, 2 = digit, 0 = anything else. */
    private static int kindOf(char c) {
        if (Character.isLetter(c)) return 1;
        if (Character.isDigit(c)) return 2;
        return 0;
    }

    private static List<String> tokens(String s) {
        if (s == null || s.isBlank()) return List.of();
        // Keep model-number tokens like "S24" / "M3" by lowering the length floor.
        return java.util.Arrays.stream(s.split("\\s+")).filter(t -> t.length() >= 2).toList();
    }

    /** The query's most distinctive word: the longest purely-alphabetic token of
     *  length &ge; 4. We skip anything with a digit ("17", "s24") so a missing
     *  model number never becomes the broadening key, and skip short/generic
     *  stubs. Drives the graceful-recall relaxation ("iphone 17" → "iphone"). */
    static String distinctiveToken(List<String> tokens) {
        String best = null;
        for (String t : tokens) {
            if (t == null || t.length() < 4) continue;
            if (COLOUR_WORDS.contains(t)) continue;     // a colour/finish is not the product's identity
            boolean hasDigit = false;
            for (int i = 0; i < t.length(); i++) {
                if (Character.isDigit(t.charAt(i))) { hasDigit = true; break; }
            }
            if (hasDigit) continue;
            if (best == null || t.length() > best.length()) best = t;
        }
        return best;
    }

    /** True when the product name contains a token within a small edit distance of
     *  {@code distinctive} — the fuzzy equivalent of the whole-word match, so an
     *  un-dictionaried brand/model typo ("oramio"→"oraimo", "labtop"→"laptop",
     *  "samsoong"→"samsung") still counts. The edit budget scales with length: 1 for
     *  most words, 2 for long ones (&ge;8) where more letters can slip. Keying on the
     *  DISTINCTIVE word (not every token) is what keeps this precise — a generic
     *  shared token like "pro" or "15" can never trigger it. */
    static boolean nameHasFuzzyToken(Product p, String distinctive) {
        if (p == null || p.getName() == null || distinctive == null || distinctive.length() < 4) return false;
        int budget = distinctive.length() >= 8 ? 2 : 1;
        for (String tok : p.getName().toLowerCase().split("[^a-z0-9]+")) {
            if (tok.length() < 3) continue;
            if (Math.abs(tok.length() - distinctive.length()) > budget) continue;
            if (tok.equals(distinctive) || osaWithin(tok, distinctive, budget)) return true;
        }
        return false;
    }

    /** Optimal string alignment (Damerau-Levenshtein restricted to ADJACENT
     *  transpositions), bounded: returns true iff the edit distance is &le; {@code max}.
     *  Transpositions cost 1, so "oramio"↔"oraimo" is distance 1, not 2. O(a·b) with a
     *  rolling three-row buffer and a per-row early-out once the whole row exceeds max. */
    static boolean osaWithin(String a, String b, int max) {
        int la = a.length(), lb = b.length();
        if (Math.abs(la - lb) > max) return false;
        int[] prev2 = new int[lb + 1];
        int[] prev = new int[lb + 1];
        int[] cur = new int[lb + 1];
        for (int j = 0; j <= lb; j++) prev[j] = j;
        for (int i = 1; i <= la; i++) {
            cur[0] = i;
            int rowMin = cur[0];
            char ca = a.charAt(i - 1);
            for (int j = 1; j <= lb; j++) {
                char cb = b.charAt(j - 1);
                int cost = (ca == cb) ? 0 : 1;
                int v = Math.min(Math.min(prev[j] + 1, cur[j - 1] + 1), prev[j - 1] + cost);
                if (i > 1 && j > 1 && ca == b.charAt(j - 2) && a.charAt(i - 2) == cb) {
                    v = Math.min(v, prev2[j - 2] + 1);   // adjacent transposition
                }
                cur[j] = v;
                if (v < rowMin) rowMin = v;
            }
            if (rowMin > max) return false;   // whole row already over budget — can only grow
            int[] t = prev2; prev2 = prev; prev = cur; cur = t;
        }
        return prev[lb] <= max;
    }

    // ── spec facets (item 3) ────────────────────────────────────────────────────
    /** Count RAM/Storage/Display values across the matched products, sorted
     *  ascending so "8GB" precedes "12GB" and "256GB" precedes "1TB". */
    private static Map<String, List<Map<String, Object>>> computeFacets(List<Product> products) {
        Map<String, Map<String, Integer>> counts = new LinkedHashMap<>();   // dim -> value -> count
        for (Product p : products) {
            if (p == null || p.getName() == null) continue;
            for (Map.Entry<String, String> e : SpecExtractor.extract(p.getName()).entrySet()) {
                counts.computeIfAbsent(e.getKey(), k -> new java.util.HashMap<>())
                        .merge(e.getValue(), 1, Integer::sum);
            }
        }
        Map<String, List<Map<String, Object>>> out = new LinkedHashMap<>();
        for (String dim : new String[]{SpecExtractor.RAM, SpecExtractor.STORAGE, SpecExtractor.DISPLAY}) {
            Map<String, Integer> vc = counts.get(dim);
            if (vc == null || vc.isEmpty()) continue;
            List<Map<String, Object>> values = new ArrayList<>();
            vc.entrySet().stream()
                    .sorted(Comparator.comparingDouble((Map.Entry<String, Integer> en) -> SpecExtractor.numericOf(en.getKey())))
                    .forEach(en -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("value", en.getKey());
                        m.put("count", en.getValue());
                        values.add(m);
                    });
            out.put(dim, values);
        }
        return out;
    }

    /** A product matches when its parsed specs satisfy every requested filter. */
    private static boolean matchesSpecs(Product p, Map<String, String> filters) {
        if (p == null || p.getName() == null) return false;
        Map<String, String> specs = SpecExtractor.extract(p.getName());
        for (Map.Entry<String, String> f : filters.entrySet()) {
            String have = specs.get(f.getKey());
            if (have == null || !have.equalsIgnoreCase(f.getValue())) return false;
        }
        return true;
    }
}
