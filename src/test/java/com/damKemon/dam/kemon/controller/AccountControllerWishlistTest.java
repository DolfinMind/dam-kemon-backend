package com.damKemon.dam.kemon.controller;

import com.damKemon.dam.kemon.model.WishlistItem;
import com.damKemon.dam.kemon.repository.AnalyticsEventRepository;
import com.damKemon.dam.kemon.repository.PriceAlertNotificationRepository;
import com.damKemon.dam.kemon.repository.ProductRepository;
import com.damKemon.dam.kemon.repository.SavedSearchRepository;
import com.damKemon.dam.kemon.repository.WishlistItemRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AccountControllerWishlistTest {

    @Test
    void trackIntentEnablesAlertsForANewProduct() {
        WishlistItemRepository wishlist = mock(WishlistItemRepository.class);
        when(wishlist.findByUserIdAndProductId(any(), any())).thenReturn(Optional.empty());
        when(wishlist.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        ProductRepository products = mock(ProductRepository.class);
        when(products.findById(any())).thenReturn(Optional.empty());
        AccountController controller = new AccountController(mock(SavedSearchRepository.class), wishlist,
                products, mock(AnalyticsEventRepository.class), mock(PriceAlertNotificationRepository.class));
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getAttribute("authUserId")).thenReturn("u1");

        controller.addToWishlist(Map.of("productId", "p1", "alertsEnabled", true), req);

        ArgumentCaptor<WishlistItem> saved = ArgumentCaptor.forClass(WishlistItem.class);
        verify(wishlist).save(saved.capture());
        assertEquals(Boolean.TRUE, saved.getValue().getAlertsEnabled());
    }

    @Test
    void saveIntentDoesNotEnableAlerts() {
        WishlistItemRepository wishlist = mock(WishlistItemRepository.class);
        when(wishlist.findByUserIdAndProductId(any(), any())).thenReturn(Optional.empty());
        when(wishlist.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        ProductRepository products = mock(ProductRepository.class);
        when(products.findById(any())).thenReturn(Optional.empty());
        AccountController controller = new AccountController(mock(SavedSearchRepository.class), wishlist,
                products, mock(AnalyticsEventRepository.class), mock(PriceAlertNotificationRepository.class));
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getAttribute("authUserId")).thenReturn("u1");

        controller.addToWishlist(Map.of("productId", "p1", "alertsEnabled", false), req);

        ArgumentCaptor<WishlistItem> saved = ArgumentCaptor.forClass(WishlistItem.class);
        verify(wishlist).save(saved.capture());
        assertEquals(Boolean.FALSE, saved.getValue().getAlertsEnabled());
    }

    @Test
    void trackIntentEnablesAlertsOnAnExistingSavedProduct() {
        WishlistItem existing = WishlistItem.builder()
                .userId("u1")
                .productId("p1")
                .alertsEnabled(false)
                .build();
        WishlistItemRepository wishlist = mock(WishlistItemRepository.class);
        when(wishlist.findByUserIdAndProductId("u1", "p1")).thenReturn(Optional.of(existing));
        when(wishlist.save(existing)).thenReturn(existing);
        AccountController controller = new AccountController(mock(SavedSearchRepository.class), wishlist,
                mock(ProductRepository.class), mock(AnalyticsEventRepository.class),
                mock(PriceAlertNotificationRepository.class));
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getAttribute("authUserId")).thenReturn("u1");

        controller.addToWishlist(Map.of("productId", "p1", "alertsEnabled", true), req);

        assertEquals(Boolean.TRUE, existing.getAlertsEnabled());
        verify(wishlist).save(existing);
    }
}
