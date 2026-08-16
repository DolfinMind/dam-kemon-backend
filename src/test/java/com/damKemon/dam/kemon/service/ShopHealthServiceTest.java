package com.damKemon.dam.kemon.service;

import com.damKemon.dam.kemon.model.Shop;
import com.damKemon.dam.kemon.repository.ProductRepository;
import com.damKemon.dam.kemon.repository.ShopRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ShopHealthServiceTest {

    private ShopHealthService svc;
    private ProductRepository products;

    @BeforeEach
    void setUp() {
        products = mock(ProductRepository.class);
        svc = new ShopHealthService(mock(ShopRepository.class), products);
    }

    @Test
    void degradedWithOneSuccess() {
        Shop s = Shop.builder().slug("x").status("active").build();
        svc.recordRun(s, 100, null);
        assertEquals("degraded", s.getHealth());
        assertEquals(0, s.getConsecutiveFailures());
        assertFalse(s.getNeedsRetry());
    }

    @Test
    void fourSuccessesPromotesToActive() {
        Shop s = Shop.builder().slug("x").status("active").build();
        for (int i = 0; i < 4; i++) svc.recordRun(s, 100, null);
        assertEquals("active", s.getHealth());
    }

    @Test
    void threeConsecutiveFailuresDoNotBlock() {
        // The auto-disable rule is intentionally conservative: a few timeouts or a
        // transient bot-block must NOT block a shop (that used to decimate the
        // catalog). consecutiveFailures still drives the same-night retry.
        Shop s = Shop.builder().slug("x").status("active").build();
        svc.recordRun(s, 0, "timeout");
        svc.recordRun(s, 0, "timeout");
        svc.recordRun(s, 0, "timeout");
        assertEquals("active", s.getStatus()); // still active after 3
        assertEquals(3, s.getConsecutiveFailures());
        assertTrue(s.getNeedsRetry());
        assertEquals("dormant", s.getHealth()); // 0 successes in the window
    }

    @Test
    void blocksAfterFullDeadWindowWhenNoLiveCatalogProducts() {
        // A shop with 0 products in the catalog is genuinely dead — block it after
        // a full window (~a week) of zero-yield runs.
        when(products.countBySiteSlug(anyString())).thenReturn(0L);
        Shop s = Shop.builder().slug("x").status("active").build();
        for (int i = 0; i < 6; i++) {
            svc.recordRun(s, 0, "timeout");
            assertEquals("active", s.getStatus()); // not yet — window not full
        }
        svc.recordRun(s, 0, "timeout"); // 7th = full window
        assertEquals("blocked", s.getStatus());
        assertEquals("dormant", s.getHealth());
    }

    @Test
    void provenShopWithLiveProductsIsNeverBlocked() {
        // Same dead window, but the shop still holds catalog products → our
        // extractor regressed, not the shop. Keep it (dormant + retrying).
        when(products.countBySiteSlug(anyString())).thenReturn(420L);
        Shop s = Shop.builder().slug("x").status("active").build();
        for (int i = 0; i < 8; i++) svc.recordRun(s, 0, "timeout");
        assertEquals("active", s.getStatus()); // never blocked
        assertTrue(s.getNeedsRetry());
    }

    @Test
    void successResetsFailureCount() {
        Shop s = Shop.builder().slug("x").status("active").build();
        svc.recordRun(s, 0, "boom");
        svc.recordRun(s, 0, "boom");
        svc.recordRun(s, 200, null);
        assertEquals(0, s.getConsecutiveFailures());
        assertFalse(s.getNeedsRetry());
    }

    @Test
    void slidingWindowCappedAtSeven() {
        Shop s = Shop.builder().slug("x").status("active").build();
        for (int i = 0; i < 12; i++) svc.recordRun(s, 50, null);
        assertEquals(7, s.getRecentRuns().size());
    }
}
