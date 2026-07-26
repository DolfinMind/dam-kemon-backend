package com.damKemon.dam.kemon.controller;

import com.damKemon.dam.kemon.model.AffiliateClick;
import com.damKemon.dam.kemon.model.Product;
import com.damKemon.dam.kemon.model.SitePrice;
import com.damKemon.dam.kemon.repository.AffiliateClickRepository;
import com.damKemon.dam.kemon.repository.ProductRepository;
import com.damKemon.dam.kemon.service.AffiliateService;
import com.damKemon.dam.kemon.util.TrafficClassifier;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AffiliateControllerTrafficTest {

    @Test
    void classifiesClickWithoutRetainingRawIp() {
        ProductRepository products = mock(ProductRepository.class);
        AffiliateClickRepository clicks = mock(AffiliateClickRepository.class);
        AffiliateService affiliate = mock(AffiliateService.class);
        Product product = mock(Product.class);
        SitePrice price = mock(SitePrice.class);
        HttpServletRequest request = mock(HttpServletRequest.class);

        when(product.getId()).thenReturn("p1");
        when(product.getPrices()).thenReturn(List.of(price));
        when(price.getProductUrl()).thenReturn("https://shop.example/p1");
        when(price.getSiteSlug()).thenReturn("shop");
        when(price.getPrice()).thenReturn(100.0);
        when(products.findById("p1")).thenReturn(Optional.of(product));
        when(affiliate.decorate(org.mockito.ArgumentMatchers.eq("https://shop.example/p1"),
                org.mockito.ArgumentMatchers.eq("shop"), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn("https://shop.example/p1?ref=damkemon");
        when(request.getHeader("User-Agent")).thenReturn("Googlebot/2.1");
        when(request.getHeader("X-Forwarded-For")).thenReturn("66.249.66.1");

        AffiliateController controller = new AffiliateController(products, clicks, affiliate, false);
        assertEquals(302, controller.redirect("p1", "shop", null, null, request).getStatusCode().value());

        ArgumentCaptor<AffiliateClick> click = ArgumentCaptor.forClass(AffiliateClick.class);
        verify(clicks).save(click.capture());
        assertEquals(TrafficClassifier.KNOWN_BOT, click.getValue().getTrafficClass());
        assertNull(click.getValue().getIp());
        assertEquals(16, click.getValue().getIpHash().length());
    }
}
