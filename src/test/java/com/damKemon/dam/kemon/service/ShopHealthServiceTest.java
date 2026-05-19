package com.damKemon.dam.kemon.service;

import com.damKemon.dam.kemon.model.Shop;
import com.damKemon.dam.kemon.repository.ShopRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class ShopHealthServiceTest {

    private ShopHealthService svc;

    @BeforeEach
    void setUp() {
        svc = new ShopHealthService(mock(ShopRepository.class));
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
    void threeConsecutiveFailuresAutoDisable() {
        Shop s = Shop.builder().slug("x").status("active").build();
        svc.recordRun(s, 0, "timeout");
        svc.recordRun(s, 0, "timeout");
        assertEquals("active", s.getStatus()); // still active after 2
        assertTrue(s.getNeedsRetry());
        svc.recordRun(s, 0, "timeout");
        assertEquals("blocked", s.getStatus()); // flipped after 3
        assertEquals(3, s.getConsecutiveFailures());
        assertEquals("dormant", s.getHealth());
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
