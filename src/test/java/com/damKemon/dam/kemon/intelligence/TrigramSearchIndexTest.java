package com.damKemon.dam.kemon.intelligence;

import com.damKemon.dam.kemon.model.Product;
import com.damKemon.dam.kemon.repository.ProductRepository;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.test.util.ReflectionTestUtils;

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

    private TrigramSearchIndex enabledIndex() {
        TrigramSearchIndex index = new TrigramSearchIndex(repo);
        ReflectionTestUtils.setField(index, "enabled", true);
        return index;
    }

    @Test
    void rebuildUsesProjectionAndStoresIds() {
        when(repo.findAllSearchDocuments()).thenReturn(List.of(Product.builder()
                .id("s24").name("Samsung Galaxy S24 Ultra")
                .brands(List.of("Samsung")).build()));
        TrigramSearchIndex index = enabledIndex();

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
        TrigramSearchIndex index = enabledIndex();

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
        TrigramSearchIndex index = enabledIndex();

        index.rebuild();

        assertFalse(index.isReady());
        assertEquals(0, index.size());
    }
}
