package com.damKemon.dam.kemon.service;

import com.damKemon.dam.kemon.dto.SearchResponse;
import com.damKemon.dam.kemon.intelligence.ProductCategory;
import com.damKemon.dam.kemon.intelligence.QueryClassifier;
import com.damKemon.dam.kemon.intelligence.QueryExpander;
import com.damKemon.dam.kemon.intelligence.QueryIntent;
import com.damKemon.dam.kemon.intelligence.TrigramSearchIndex;
import com.damKemon.dam.kemon.model.Product;
import com.damKemon.dam.kemon.repository.ProductRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.PageImpl;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Guards the graceful-recall relaxation: a brand query for a model we don't
 * stock ("iphone 17") must still surface the right product family rather than
 * collapsing to accessories-only — without dragging in off-brand products.
 */
class CatalogSearchServiceRecallTest {

    @SuppressWarnings("unchecked")
    private CatalogSearchService serviceWith(List<Product> catalog) {
        ProductRepository repo = mock(ProductRepository.class);
        QueryClassifier classifier = mock(QueryClassifier.class);
        QueryExpander expander = mock(QueryExpander.class);
        TrigramSearchIndex trigram = mock(TrigramSearchIndex.class);
        AtlasSearchService atlas = mock(AtlasSearchService.class);

        // The three recall passes all see the same candidate pool.
        when(repo.textSearch(anyString(), any(Pageable.class))).thenReturn(catalog);
        when(repo.findByNamePrefix(anyString(), any(Pageable.class))).thenReturn(catalog);
        when(repo.findByNameContainingIgnoreCase(anyString())).thenReturn(catalog);
        when(repo.findByCategoryIgnoreCase(anyString(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));
        when(repo.findActiveSponsoredByCategory(any(), anyString(), any(Pageable.class))).thenReturn(List.of());
        when(repo.findActiveSponsored(any(), any(Pageable.class))).thenReturn(List.of());

        // Smartphone intent, no brand detected (the real-world gap: "iphone" is
        // not registered as a brand, only "apple").
        QueryIntent intent = QueryIntent.builder()
                .categories(new ArrayList<>(List.of(ProductCategory.SMARTPHONE)))
                .confidence(0.6)
                .build();
        when(classifier.classify(anyString())).thenReturn(intent);

        // Identity normalisation/expansion — keeps the test about recall, not synonyms.
        when(expander.normalizeBengali(anyString())).thenAnswer(i -> i.getArgument(0));
        when(expander.collapsePhrases(anyString())).thenAnswer(i -> i.getArgument(0));
        when(expander.expandTokens(any())).thenAnswer(i ->
                new LinkedHashSet<>((Collection<String>) i.getArgument(0)));

        when(trigram.isEnabled()).thenReturn(false);
        when(atlas.isEnabled()).thenReturn(false);

        CatalogSearchService svc = new CatalogSearchService(repo, classifier, expander, trigram, atlas,
                new ShopVisibilityService(mock(com.damKemon.dam.kemon.repository.ShopRepository.class)));
        ReflectionTestUtils.setField(svc, "pageSize", 30);
        ReflectionTestUtils.setField(svc, "maxPageSize", 60);
        ReflectionTestUtils.setField(svc, "maxCandidates", 300);
        return svc;
    }

    private static Product product(String id, String name, String category, double price) {
        return Product.builder().id(id).name(name).category(category).lowestPrice(price).build();
    }

    @Test
    void missingModelStillRecallsTheRightFamily() {
        List<Product> catalog = List.of(
                product("p16", "Apple iPhone 16 Plus 128GB", "smartphones", 120000),
                product("c17", "Spigen Liquid Case for iPhone 17 Series", "accessories", 1500),
                product("s24", "Samsung Galaxy S24 Ultra 256GB", "smartphones", 150000));

        SearchResponse resp = serviceWith(catalog).search("iphone 17", 0, 20);
        List<String> names = resp.getProducts().stream().map(Product::getName).toList();

        // Before the fix this collapsed to the accessory only (iPhone 16 fails the
        // 60% gate on the unmatched "17"). The relaxation pulls the related PHONE
        // back in, which is the point: a model we don't stock still surfaces its family.
        assertTrue(names.stream().anyMatch(n -> n.contains("iPhone 16")),
                "related iPhone phone should be recalled, not just the iPhone-17 accessory; got " + names);
        // ...and the iPhone-17 item here is a CASE, so the default device search
        // (accessories hidden) must drop it — searching "iphone 17" shows phones,
        // not Spigen cases. (Pass acc=true to bring accessories back.)
        assertFalse(names.stream().anyMatch(n -> n.contains("Case")),
                "an accessory must be hidden on a default device search; got " + names);
        // Broadening keys off the distinctive word "iphone", so off-brand stays out.
        assertFalse(names.stream().anyMatch(n -> n.contains("Samsung")),
                "off-brand product must not be pulled in by the relaxation; got " + names);
    }
}
