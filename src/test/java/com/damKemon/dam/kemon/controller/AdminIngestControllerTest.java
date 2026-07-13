package com.damKemon.dam.kemon.controller;

import com.damKemon.dam.kemon.indexer.BulkIndexer;
import com.damKemon.dam.kemon.model.Shop;
import com.damKemon.dam.kemon.repository.ShopRepository;
import com.damKemon.dam.kemon.scraper.ScrapedProduct;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminIngestControllerTest {

    private BulkIndexer indexer;
    private ShopRepository shops;
    private AdminIngestController controller;

    @BeforeEach
    void setUp() {
        indexer = mock(BulkIndexer.class);
        shops = mock(ShopRepository.class);
        controller = new AdminIngestController(indexer, shops);
    }

    @Test
    void rejectsMoreThan250Offers() {
        List<AdminIngestController.IngestOffer> offers = new ArrayList<>();
        for (int i = 0; i < 251; i++) {
            offers.add(offer("Phone " + i, 1000.0, "https://shop.test/p/" + i));
        }
        AdminIngestController.IngestRequest request = new AdminIngestController.IngestRequest(
                List.of(new AdminIngestController.IngestBatch("shop", offers)));

        ResponseEntity<?> response = controller.ingest(request);

        assertEquals(413, response.getStatusCode().value());
    }

    @Test
    void reportsInvalidAndAcceptedOffersWithoutSendingBadRows() {
        Shop shop = Shop.builder().slug("shop").name("Shop").build();
        when(shops.findBySlug("shop")).thenReturn(Optional.of(shop));
        when(indexer.enrichFast(eq(shop), any())).thenReturn(
                new BulkIndexer.FastIngestResult(1, 1, 1, 0, 0));
        List<AdminIngestController.IngestOffer> offers = List.of(
                offer("Phone X", 1000.0, "https://shop.test/p/x"),
                offer("Missing URL", 1000.0, null),
                offer("Bad price", 1.0, "https://shop.test/p/bad"));
        AdminIngestController.IngestRequest request = new AdminIngestController.IngestRequest(
                List.of(new AdminIngestController.IngestBatch("shop", offers)));

        ResponseEntity<?> response = controller.ingest(request);

        assertEquals(200, response.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertEquals(3, body.get("submitted"));
        assertEquals(2, body.get("invalid"));
        assertEquals(1, body.get("accepted"));
        assertEquals(1, body.get("inserted"));
        assertEquals(0, body.get("merged"));
        assertEquals(0, body.get("outOfScope"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ScrapedProduct>> captor = ArgumentCaptor.forClass(List.class);
        verify(indexer).enrichFast(eq(shop), captor.capture());
        assertEquals(1, captor.getValue().size());
    }

    private static AdminIngestController.IngestOffer offer(String name, Double price, String url) {
        return new AdminIngestController.IngestOffer(
                name, price, null, url, null, true, null, null);
    }
}
