package com.damKemon.dam.kemon.controller;

import com.damKemon.dam.kemon.model.PendingOffer;
import com.damKemon.dam.kemon.model.Product;
import com.damKemon.dam.kemon.model.SitePrice;
import com.damKemon.dam.kemon.repository.PendingOfferRepository;
import com.damKemon.dam.kemon.repository.ProductRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OffersControllerTest {

    private Product productWith(double... prices) {
        List<SitePrice> sp = new ArrayList<>();
        for (double p : prices) sp.add(SitePrice.builder().siteSlug("s" + p).price(p).build());
        Product prod = new Product();
        prod.setId("p1");
        prod.setName("Test phone");
        prod.setPrices(sp);
        prod.setLowestPrice(prices.length == 0 ? null : prices[0]);
        return prod;
    }

    @Test
    void approveAddsOfferAsSitePriceAndRecomputesLowest() {
        ProductRepository products = mock(ProductRepository.class);
        PendingOfferRepository offers = mock(PendingOfferRepository.class);
        when(offers.findById("o1")).thenReturn(Optional.of(PendingOffer.builder()
                .id("o1").productId("p1").shopName("Cheap BD").url("https://cheap.bd/x").price(80.0).status("pending").build()));
        when(products.findById("p1")).thenReturn(Optional.of(productWith(100.0)));

        var resp = new OffersController(offers, products).approve("o1");

        assertEquals(200, resp.getStatusCode().value());
        ArgumentCaptor<Product> saved = ArgumentCaptor.forClass(Product.class);
        verify(products).save(saved.capture());
        assertEquals(2, saved.getValue().getPrices().size(), "offer should be appended as a price row");
        assertEquals(80.0, saved.getValue().getLowestPrice(), "lowest price should drop to the cheaper offer");
    }

    @Test
    void submitRejectsNonPositivePrice() {
        ProductRepository products = mock(ProductRepository.class);
        PendingOfferRepository offers = mock(PendingOfferRepository.class);
        when(products.findById("p1")).thenReturn(Optional.of(productWith(100.0)));

        var resp = new OffersController(offers, products).submit("p1",
                Map.of("shopName", "Cheap BD", "url", "https://cheap.bd/x", "price", 0),
                "anon", mock(HttpServletRequest.class));

        assertEquals(400, resp.getStatusCode().value());
        verify(offers, never()).save(any());
    }
}
