package com.damKemon.dam.kemon.service;

import com.damKemon.dam.kemon.dto.SearchResponse;
import com.damKemon.dam.kemon.intelligence.QueryClassifier;
import com.damKemon.dam.kemon.intelligence.QueryExpander;
import com.damKemon.dam.kemon.intelligence.QueryIntent;
import com.damKemon.dam.kemon.intelligence.TrigramSearchIndex;
import com.damKemon.dam.kemon.model.Product;
import com.damKemon.dam.kemon.repository.ProductRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Golden typo-recall suite — the permanent guardrail against the "0 products for a
 * product that exists" class of bug.
 *
 * <p><b>Why a REAL trigram index.</b> The predecessor of this test mocked the
 * trigram hit with a hand-picked score of 0.31, which cleared the old 0.25
 * "did you mean" cliff — so it stayed green while prod returned 0. The real
 * Jaccard for a correct match on a long BD product name is ~0.14 (length-biased),
 * which fell into a dead band: high enough to be a candidate, too low to survive
 * the whole-word gate OR the rescue. These tests build a real {@link TrigramSearchIndex}
 * over realistic long names, so the scores are the ones prod actually computes —
 * they fail on the pre-fix code and pass on the length-independent coverage fix.
 *
 * <p>Add every future failing query here with the product it must surface; a
 * regression then fails the build instead of shipping.
 */
class CatalogSearchTypoRecallTest {

    @SuppressWarnings("unchecked")
    private CatalogSearchService serviceWith(List<Product> catalog) {
        ProductRepository repo = mock(ProductRepository.class);
        QueryClassifier classifier = mock(QueryClassifier.class);
        QueryExpander expander = mock(QueryExpander.class);
        AtlasSearchService atlas = mock(AtlasSearchService.class);

        when(repo.findAllSearchDocuments()).thenReturn(catalog);
        when(repo.findAllById(any())).thenAnswer(invocation -> {
            Iterable<String> requested = invocation.getArgument(0);
            Set<String> ids = new HashSet<>();
            requested.forEach(ids::add);
            return catalog.stream().filter(p -> ids.contains(p.getId())).toList();
        });
        // $text can't do typos (no token "oramio" is indexed) — recall is purely the
        // trigram index, exactly as in prod with Atlas off.
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
        when(atlas.isEnabled()).thenReturn(false);

        // REAL trigram index over the catalog: the scores are the true, length-biased
        // Jaccard + length-independent coverage prod sees, NOT hand-picked fixtures.
        TrigramSearchIndex trigram = new TrigramSearchIndex(repo, true);
        trigram.rebuild();

        CatalogSearchService svc = new CatalogSearchService(repo, classifier, expander, trigram, atlas,
                new ShopVisibilityService(mock(com.damKemon.dam.kemon.repository.ShopRepository.class)));
        ReflectionTestUtils.setField(svc, "pageSize", 30);
        ReflectionTestUtils.setField(svc, "maxPageSize", 60);
        ReflectionTestUtils.setField(svc, "maxCandidates", 300);
        return svc;
    }

    private static Product product(String id, String name, String brand, String category, double price) {
        return Product.builder().id(id).name(name)
                .brands(brand == null ? List.of() : List.of(brand))
                .category(category).lowestPrice(price).build();
    }

    /** Realistic BD catalog — the long, glued, spec-laden names are the whole point:
     *  they are what tanks the length-biased Jaccard the old code relied on. */
    private List<Product> catalog() {
        return List.of(
            product("v1", "Oraimo CordFlex 30W USB-C to USB-C Fast Charging Cable 1M White", "Oraimo", "accessories", 350),
            product("s1", "Samsung Galaxy S24 Ultra 5G 12/256GB Titanium Black", "Samsung", "smartphones", 165000),
            product("l1", "ASUS VivoBook 15 X1502VA Core i5 8GB 512GB Laptop", "Asus", "laptops", 78000),
            product("n1", "DuraFlex Flip-up Clip-on Polarized Sunglasses", null, "general", 800),
            product("n2", "Airy A1 True Wireless Bluetooth Earbuds", "Airy", "audio", 1200)
        );
    }

    /** The reported bug verbatim: a transposed brand ("oramio"↔"oraimo") plus a
     *  glued compound ("cord flex"↔"CordFlex") on a long name — used to return 0. */
    @Test
    void brandTypoTransposition_oramioCordFlex_returnsTheOraimoProduct() {
        SearchResponse resp = serviceWith(catalog()).search("oramio cord flex", 0, 20);
        List<String> names = resp.getProducts().stream().map(Product::getName).toList();
        assertFalse(names.isEmpty(), "results page must not be empty when the product exists");
        assertEquals("v1", resp.getProducts().get(0).getId(),
                "the Oraimo product must rank first; got " + names);
        assertEquals("Oraimo CordFlex 30W USB-C to USB-C Fast Charging Cable 1M White",
                resp.getDidYouMean(), "a 'did you mean' should point at the real product");
    }

    /** Two-word typo, un-dictionaried: "samsoong galxy" → Samsung Galaxy. */
    @Test
    void brandTypo_samsoongGalxy_returnsSamsungGalaxy() {
        SearchResponse resp = serviceWith(catalog()).search("samsoong galxy", 0, 20);
        List<String> names = resp.getProducts().stream().map(Product::getName).toList();
        assertFalse(names.isEmpty(), "must not be empty");
        assertEquals("s1", resp.getProducts().get(0).getId(),
                "Samsung Galaxy must surface for a two-word typo; got " + names);
    }

    /** Short single-token typo whose Jaccard is far below the recall floor
     *  ("labtop" vs a long laptop name ~0.07) — coverage is what rescues it. */
    @Test
    void categoryTypo_labtop_returnsTheLaptop() {
        SearchResponse resp = serviceWith(catalog()).search("labtop", 0, 20);
        List<String> ids = resp.getProducts().stream().map(Product::getId).toList();
        assertTrue(ids.contains("l1"),
                "a short typo of 'laptop' must still find the laptop; got " + ids);
    }

    /** Precision floor: true gibberish shares no meaningful trigrams, so it must
     *  still return nothing rather than dragging in fuzzy noise. */
    @Test
    void gibberish_returnsNothing() {
        SearchResponse resp = serviceWith(catalog()).search("qwzxvbn", 0, 20);
        assertTrue(resp.getProducts().isEmpty(),
                "gibberish must not drag in trigram noise; got "
                        + resp.getProducts().stream().map(Product::getName).toList());
    }
}
