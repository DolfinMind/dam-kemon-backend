package com.damKemon.dam.kemon.controller;

import com.damKemon.dam.kemon.repository.ProductRepository;
import com.damKemon.dam.kemon.service.CategoryFocusService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SitemapControllerTest {

    @Mock ProductRepository products;
    @Mock CategoryFocusService focus;

    private SitemapController controller;

    @BeforeEach
    void setUp() {
        controller = new SitemapController(products, focus);
        ReflectionTestUtils.setField(controller, "webUrl", "https://damkemon.com");
    }

    @Test
    void productSitemapUsesFocusedCatalogAndStableCategoryPages() {
        when(focus.isEnabled()).thenReturn(true);
        when(focus.allowedLabels()).thenReturn(Set.of("smartphones", "laptops"));
        ProductRepository.SlugView row = mock(ProductRepository.SlugView.class);
        when(row.getSlug()).thenReturn("galaxy-s24");
        when(products.countByCategoryIn(any())).thenReturn(1L);
        when(products.findSlugViewsByCategoryIn(any(), any(Pageable.class))).thenReturn(List.of(row));

        String xml = controller.sitemap(0).getBody();

        assertNotNull(xml);
        assertTrue(xml.contains("/category/smartphones"));
        assertTrue(xml.contains("/category/desktops%20%26%20pc"));
        assertTrue(xml.contains("/product/galaxy-s24"));
        assertFalse(xml.contains("/compare"));
        assertFalse(xml.contains("/submit-shop"));
        verify(products).findSlugViewsByCategoryIn(eq(Set.of("smartphones", "laptops")), any(Pageable.class));
        verify(products, never()).findAllSlugViews(any());
    }

    @Test
    void robotsLetsCrawlerObserveNoindexOnTrackedRedirects() {
        String body = controller.robots().getBody();

        assertNotNull(body);
        assertTrue(body.contains("Allow: /api/r/"));
        assertTrue(body.contains("Disallow: /api/"));
        assertFalse(body.contains("Disallow: /api/r/"));
    }
}
