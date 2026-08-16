package com.damKemon.dam.kemon.intelligence;

import com.damKemon.dam.kemon.model.Product;
import com.damKemon.dam.kemon.repository.ProductRepository;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataRetrievalFailureException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TrigramSearchIndexTest {

    private final ProductRepository repo = mock(ProductRepository.class);

    private TrigramSearchIndex index() {
        return new TrigramSearchIndex(repo, true);
    }

    @Test
    void disabledIndexSkipsStartupAndScheduledRebuilds() {
        TrigramSearchIndex index = new TrigramSearchIndex(repo, false);

        index.run(null);
        index.hourlyRefresh();

        assertFalse(index.isEnabled());
        assertTrue(index.isReady());
        assertTrue(index.topK("iphone", 5, 0).isEmpty());
        verify(repo, never()).findAllSearchDocuments();
    }

    @Test
    void rebuildUsesProjectionAndStoresIds() {
        when(repo.findAllSearchDocuments()).thenReturn(List.of(Product.builder()
                .id("s24").name("Samsung Galaxy S24 Ultra")
                .brands(List.of("Samsung")).build()));
        TrigramSearchIndex index = index();

        index.rebuild();

        assertTrue(index.isReady());
        assertEquals("s24", index.topK("samsung galaxy", 5, 0).get(0).id());
        verify(repo, never()).findAll();
    }

    @Test
    void failedRefreshKeepsLastGoodIndex() {
        Product phone = Product.builder().id("s24").name("Samsung Galaxy S24 Ultra").build();
        when(repo.findAllSearchDocuments()).thenReturn(List.of(phone))
                .thenThrow(new DataRetrievalFailureException("mongo unavailable"));
        TrigramSearchIndex index = index();

        index.rebuild();
        index.rebuild();

        assertTrue(index.isReady());
        assertEquals(1, index.size());
        assertEquals("DataRetrievalFailureException", index.status().get("lastFailure"));
    }

    @Test
    void nonEmptyCatalogCannotBecomeReadyWithEmptyIndex() {
        when(repo.findAllSearchDocuments()).thenReturn(List.of());
        when(repo.count()).thenReturn(1L);
        TrigramSearchIndex index = index();

        index.rebuild();

        assertFalse(index.isReady());
        assertEquals(0, index.size());
    }
}
