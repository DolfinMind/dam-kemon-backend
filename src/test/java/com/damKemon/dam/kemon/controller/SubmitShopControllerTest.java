package com.damKemon.dam.kemon.controller;

import com.damKemon.dam.kemon.model.PendingShop;
import com.damKemon.dam.kemon.repository.PendingShopRepository;
import com.damKemon.dam.kemon.repository.ShopRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SubmitShopControllerTest {

    private SubmitShopController controller;
    private PendingShopRepository pending;
    private ShopRepository shops;

    @BeforeEach
    void setUp() {
        pending = mock(PendingShopRepository.class);
        shops = mock(ShopRepository.class);
        when(shops.findAll()).thenReturn(List.of());
        when(pending.findByBaseUrl(anyString())).thenReturn(Optional.empty());
        when(pending.save(any(PendingShop.class))).thenAnswer(inv -> inv.getArgument(0));
        controller = new SubmitShopController(pending, shops);
    }

    @Test
    void rejectsMissingName() {
        var resp = controller.submit(Map.of("baseUrl", "https://example.com"));
        assertEquals(400, resp.getStatusCode().value());
    }

    @Test
    void rejectsBadUrl() {
        var resp = controller.submit(Map.of(
                "name", "Test Shop",
                "baseUrl", "not-a-url"));
        assertEquals(400, resp.getStatusCode().value());
    }

    @Test
    void acceptsValidSubmission() {
        var resp = controller.submit(Map.of(
                "name", "Test Shop",
                "baseUrl", "https://testshop.example/",
                "sitemapUrl", "https://testshop.example/sitemap.xml",
                "contactEmail", "owner@testshop.example"));
        assertEquals(202, resp.getStatusCode().value());
        assertNotNull(resp.getBody());
        assertEquals(true, ((Map<?, ?>) resp.getBody()).get("submitted"));
    }

    @Test
    void rejectsDuplicateSubmission() {
        when(pending.findByBaseUrl("https://test.example")).thenReturn(Optional.of(new PendingShop()));
        var resp = controller.submit(Map.of(
                "name", "Dup Shop",
                "baseUrl", "https://test.example/",
                "contactEmail", "x@y.z"));
        assertEquals(409, resp.getStatusCode().value());
    }
}
