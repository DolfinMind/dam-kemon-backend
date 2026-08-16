package com.damKemon.dam.kemon.config;

import com.damKemon.dam.kemon.intelligence.TrigramSearchIndex;
import com.damKemon.dam.kemon.service.SyntheticMonitorService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SyntheticHealthIndicatorTest {

    private final SyntheticMonitorService monitor = mock(SyntheticMonitorService.class);
    private final TrigramSearchIndex trigram = mock(TrigramSearchIndex.class);

    @Test
    void unavailableTrigramMakesHealthDownImmediately() {
        when(trigram.isReady()).thenReturn(false);
        when(trigram.status()).thenReturn(Map.of("ready", false, "size", 0));

        Health health = new SyntheticHealthIndicator(monitor, trigram).health();

        assertEquals(Status.DOWN, health.getStatus());
        assertEquals(trigram.status(), health.getDetails().get("trigram"));
    }

    @Test
    void readyTrigramKeepsSyntheticUp() {
        when(trigram.isReady()).thenReturn(true);
        when(trigram.status()).thenReturn(Map.of("ready", true, "size", 50));
        when(monitor.latest()).thenReturn(Map.of("ok", true));

        Health health = new SyntheticHealthIndicator(monitor, trigram).health();

        assertEquals(Status.UP, health.getStatus());
        assertEquals(trigram.status(), health.getDetails().get("trigram"));
    }
}
