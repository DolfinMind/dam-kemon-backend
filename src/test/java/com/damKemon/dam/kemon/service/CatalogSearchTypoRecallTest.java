package com.damKemon.dam.kemon.service;

import com.damKemon.dam.kemon.dto.SearchResponse;
import com.damKemon.dam.kemon.intelligence.QueryClassifier;
import com.damKemon.dam.kemon.intelligence.QueryExpander;
import com.damKemon.dam.kemon.intelligence.QueryIntent;
import com.damKemon.dam.kemon.intelligence.TrigramIndex;
import com.damKemon.dam.kemon.intelligence.TrigramSearchIndex;
import com.damKemon.dam.kemon.model.Product;
import com.damKemon.dam.kemon.repository.ProductRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.PageImpl;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Guards the zero-results fallback. A brand-typo query ("oramio cord flex" for
 * "Oraimo CordForce Flex") is found by the trigram index — /suggest shows it —
 * but the whole-word token gate rejects every hit ("oramio"≠"Oraimo", "cord"≠
 * "CordForce"), so the results page used to return 0 while the dropdown showed
 * the product. The fallback admits the fuzzy best when raw would otherwise empty.
 */
class CatalogSearchTypoRecallTest {

    @SuppressWarnings("unchecked")
    private CatalogSearchService serviceWith(List<TrigramIndex.Hit> fuzzy) {
        ProductRepository repo = mock(ProductRepository.class);
        QueryClassifier classifier = mock(QueryClassifier.class);
        QueryExpander expander = mock(QueryExpander.class);
        TrigramSearchIndex trigram = mock(TrigramSearchIndex.class);
        AtlasSearchService atlas = mock(AtlasSearchService.class);

        // $text finds nothing for the typo (no token "oramio" is indexed) — recall
        // is purely the trigram index, exactly as in prod when Atlas is off.
        when(repo.textSearch(anyString(), any(Pageable.class))).thenReturn(List.of());
        when(repo.findByNamePrefix(anyString(), any(Pageable.class))).thenReturn(List.of());
        when(repo.findByCategoryIgnoreCase(anyString(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));
        when(repo.findActiveSponsoredByCategory(any(), anyString(), any(Pageable.class))).thenReturn(List.of());
        when(repo.findActiveSponsored(any(), any(Pageable.class))).thenReturn(List.of());

        // "General" intent, no brand — the real classification for an unknown brand typo.
        when(classifier.classify(anyString())).thenReturn(QueryIntent.builder().confidence(0.3).build());

        when(expander.normalizeBengali(anyString())).thenAnswer(i -> i.getArgument(0));
        when(expander.collapsePhrases(anyString())).thenAnswer(i -> i.getArgument(0));
        when(expander.expandTokens(any())).thenAnswer(i ->
                new LinkedHashSet<>((Collection<String>) i.getArgument(0)));

        // Trigram is the only recall + the fallback source; it returns the same hits
        // /suggest would show. topK is called for both recall and the fallback.
        when(trigram.isEnabled()).thenReturn(true);
        when(trigram.topK(anyString(), anyInt(), anyDouble())).thenReturn(fuzzy);
        when(atlas.isEnabled()).thenReturn(false);

        CatalogSearchService svc = new CatalogSearchService(repo, classifier, expander, trigram, atlas);
        ReflectionTestUtils.setField(svc, "pageSize", 30);
        ReflectionTestUtils.setField(svc, "maxPageSize", 60);
        ReflectionTestUtils.setField(svc, "maxCandidates", 300);
        return svc;
    }

    private static Product product(String id, String name, String category, double price) {
        return Product.builder().id(id).name(name).category(category).lowestPrice(price).build();
    }

    @Test
    void brandTypoStillReturnsTheProductInsteadOfZero() {
        Product oraimo = product("v1", "Oraimo CordForce Flex Corded 2-in-1 Stick Vacuum", "home appliances", 4000);
        Product noise  = product("s1", "DuraFlex Flip-up", "general", 1250);
        // Trigram-ranked hits (score desc): the typo is visibly close to Oraimo.
        List<TrigramIndex.Hit> fuzzy = List.of(
                new TrigramIndex.Hit("v1", 0.31, oraimo),
                new TrigramIndex.Hit("s1", 0.14, noise));

        SearchResponse resp = serviceWith(fuzzy).search("oramio cord flex", 0, 20);
        List<String> names = resp.getProducts().stream().map(Product::getName).toList();

        // The dropdown showed Oraimo but the results page returned 0 — that divergence
        // is the bug. The fallback must surface it, ranked first over the "Flex" noise.
        assertFalse(names.isEmpty(), "results page must not be empty when /suggest finds a match");
        assertTrue(names.get(0).startsWith("Oraimo"),
                "the closest product must rank first; got " + names);
        assertEquals("Oraimo CordForce Flex Corded 2-in-1 Stick Vacuum", resp.getDidYouMean(),
                "a 'did you mean' should point at the real product");
    }
}
