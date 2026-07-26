package com.damKemon.dam.kemon.controller;

import com.damKemon.dam.kemon.dto.SearchResponse;
import com.damKemon.dam.kemon.service.AnalyticsService;
import com.damKemon.dam.kemon.service.CatalogSearchService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SearchControllerTest {

    @Test
    void searchDelegatesToCatalogAndRecordsAnalytics() {
        CatalogSearchService catalog = mock(CatalogSearchService.class);
        AnalyticsService analytics = mock(AnalyticsService.class);
        SearchResponse fake = SearchResponse.builder().query("iphone").totalResults(12).build();
        when(catalog.search(eq("iphone"), eq(0), eq(0), eq(false), any())).thenReturn(fake);

        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getRemoteAddr()).thenReturn("203.0.113.5");
        when(req.getAttribute("authUserId")).thenReturn("user-7");
        when(req.getHeader("User-Agent")).thenReturn("Mozilla/5.0 Chrome/126");

        SearchController ctrl = new SearchController(catalog, analytics);
        var resp = ctrl.search("iphone", 0, null, false, null, null, null, "anon-id-123", req);

        assertEquals(200, resp.getStatusCode().value());
        assertNotNull(resp.getBody());
        assertEquals(12, resp.getBody().getTotalResults());

        ArgumentCaptor<String> ipCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Long> latencyCap = ArgumentCaptor.forClass(Long.class);
        verify(analytics).recordSearch(eq("iphone"), eq(12), eq("anon-id-123"),
                ipCap.capture(), eq("user-7"), latencyCap.capture(), anyList(),
                eq("Mozilla/5.0 Chrome/126"));
        assertEquals("203.0.113.5", ipCap.getValue());
        // latency should be a small non-negative number for a mocked call
        assertNotNull(latencyCap.getValue());
    }

    @Test
    void searchUsesXForwardedForWhenPresent() {
        CatalogSearchService catalog = mock(CatalogSearchService.class);
        AnalyticsService analytics = mock(AnalyticsService.class);
        when(catalog.search(anyString(), anyInt(), anyInt(), anyBoolean(), any())).thenReturn(SearchResponse.builder().totalResults(0).build());

        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeader("X-Forwarded-For")).thenReturn("1.2.3.4, 5.6.7.8");

        SearchController ctrl = new SearchController(catalog, analytics);
        ctrl.search("x", 0, null, false, null, null, null, null, req);

        verify(analytics).recordSearch(eq("x"), eq(0), eq(null), eq("1.2.3.4"),
                eq(null), org.mockito.ArgumentMatchers.anyLong(), anyList(), eq(null));
    }
}
