package com.damKemon.dam.kemon.service;

import com.damKemon.dam.kemon.model.AnalyticsEvent;
import com.damKemon.dam.kemon.repository.AnalyticsEventRepository;
import com.damKemon.dam.kemon.util.TrafficClassifier;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AnalyticsServiceTrafficTest {

    @Test
    void classifiesBeforeDiscardingRawIp() {
        AnalyticsEventRepository repository = mock(AnalyticsEventRepository.class);
        AnalyticsService service = new AnalyticsService(repository, false);

        service.recordView("p1", "a1", "66.249.66.1", null,
                "Mozilla/5.0 Chrome/126 Safari/537.36");

        ArgumentCaptor<AnalyticsEvent> event = ArgumentCaptor.forClass(AnalyticsEvent.class);
        verify(repository).save(event.capture());
        assertEquals(TrafficClassifier.SUSPECTED_BOT, event.getValue().getTrafficClass());
        assertNull(event.getValue().getIp());
        assertEquals(16, event.getValue().getIpHash().length());
    }
}
