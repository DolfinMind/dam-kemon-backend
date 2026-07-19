package com.damKemon.dam.kemon.controller;

import com.damKemon.dam.kemon.repository.ProductRepository;
import com.damKemon.dam.kemon.repository.ShopRepository;
import com.damKemon.dam.kemon.service.AdminAnalyticsService;
import com.damKemon.dam.kemon.service.AnalyticsService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AnalyticsControllerActionTest {

    @Test
    void acceptsKnownConversionActionsAndRejectsArbitraryTypes() {
        AnalyticsService analytics = mock(AnalyticsService.class);
        AnalyticsController controller = new AnalyticsController(analytics,
                mock(AdminAnalyticsService.class), mock(ProductRepository.class), mock(ShopRepository.class));
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getAttribute("authUserId")).thenReturn("u1");

        assertEquals(204, controller.action(Map.of(
                "type", "member_action_completed_track", "productId", "p1", "anonId", "a1"), req)
                .getStatusCode().value());
        verify(analytics).recordAction(eq("member_action_completed_track"), eq("p1"), eq("a1"), nullable(String.class), eq("u1"));

        assertEquals(400, controller.action(Map.of("type", "anything_goes"), req).getStatusCode().value());
        verify(analytics, never()).recordAction(eq("anything_goes"), any(), any(), any(), any());
    }
}
