package com.damKemon.dam.kemon.controller;

import com.damKemon.dam.kemon.service.CrawlerControlService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdminCrawlerControllerTest {

    @Test
    void remoteUnauthorizedIsA502NotAnOwnerSession401() {
        CrawlerControlService crawler = mock(CrawlerControlService.class);
        when(crawler.status()).thenReturn(
                new CrawlerControlService.RemoteResponse(401, "{\"error\":\"unauthorized\"}"));

        var response = new AdminCrawlerController(crawler).status();

        assertEquals(502, response.getStatusCode().value());
        assertEquals("{\"error\":\"crawler bridge authorization failed\"}", response.getBody());
    }
}
