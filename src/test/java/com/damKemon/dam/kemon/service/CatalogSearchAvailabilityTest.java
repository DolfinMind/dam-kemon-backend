package com.damKemon.dam.kemon.service;

import com.damKemon.dam.kemon.intelligence.QueryClassifier;
import com.damKemon.dam.kemon.intelligence.QueryExpander;
import com.damKemon.dam.kemon.intelligence.TrigramSearchIndex;
import com.damKemon.dam.kemon.repository.ProductRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CatalogSearchAvailabilityTest {

    @Test
    void unavailableTrigramReturnsServiceUnavailable() {
        TrigramSearchIndex trigram = mock(TrigramSearchIndex.class);
        when(trigram.isReady()).thenReturn(false);
        CatalogSearchService service = new CatalogSearchService(
                mock(ProductRepository.class), mock(QueryClassifier.class),
                mock(QueryExpander.class), trigram, mock(AtlasSearchService.class),
                mock(ShopVisibilityService.class));

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.search("iphone 14"));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, error.getStatusCode());
    }
}
