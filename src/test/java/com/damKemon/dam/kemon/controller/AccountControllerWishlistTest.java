package com.damKemon.dam.kemon.controller;

import com.damKemon.dam.kemon.model.WishlistItem;
import com.damKemon.dam.kemon.model.Product;
import com.damKemon.dam.kemon.repository.AnalyticsEventRepository;
import com.damKemon.dam.kemon.repository.PriceAlertNotificationRepository;
import com.damKemon.dam.kemon.repository.ProductRepository;
import com.damKemon.dam.kemon.repository.SavedSearchRepository;
import com.damKemon.dam.kemon.repository.WishlistItemRepository;
import com.damKemon.dam.kemon.service.AnalyticsService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;

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
        when(products.findById(any())).thenReturn(Optional.of(Product.builder().id("p1").lowestPrice(100d).build()));
        AccountController controller = new AccountController(mock(SavedSearchRepository.class), wishlist,
                products, mock(AnalyticsEventRepository.class), mock(PriceAlertNotificationRepository.class), mock(AnalyticsService.class));
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
                products, mock(AnalyticsEventRepository.class), mock(PriceAlertNotificationRepository.class), mock(AnalyticsService.class));
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
        ProductRepository products = mock(ProductRepository.class);
        when(products.findById(any())).thenReturn(Optional.of(Product.builder().id("p1").lowestPrice(100d).build()));
        AccountController controller = new AccountController(mock(SavedSearchRepository.class), wishlist,
                products, mock(AnalyticsEventRepository.class),
                mock(PriceAlertNotificationRepository.class), mock(AnalyticsService.class));
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getAttribute("authUserId")).thenReturn("u1");

        controller.addToWishlist(Map.of("productId", "p1", "alertsEnabled", true), req);

        assertEquals(Boolean.TRUE, existing.getAlertsEnabled());
        verify(wishlist).save(existing);
    }

    @Test
    void targetedAlertStoresTargetAndRejectsTargetAtCurrentPrice() {
        WishlistItemRepository wishlist = mock(WishlistItemRepository.class);
        when(wishlist.findByUserIdAndProductId(any(), any())).thenReturn(Optional.empty());
        when(wishlist.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        ProductRepository products = mock(ProductRepository.class);
        when(products.findById("p1")).thenReturn(Optional.of(
                Product.builder().id("p1").lowestPrice(100d).build()));
        AnalyticsService analytics = mock(AnalyticsService.class);
        AccountController controller = new AccountController(mock(SavedSearchRepository.class), wishlist,
                products, mock(AnalyticsEventRepository.class),
                mock(PriceAlertNotificationRepository.class), analytics);
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getAttribute("authUserId")).thenReturn("u1");

        ResponseEntity<?> accepted = controller.addToWishlist(
                Map.of("productId", "p1", "targetPrice", 90d), req);
        assertEquals(200, accepted.getStatusCode().value());
        ArgumentCaptor<WishlistItem> saved = ArgumentCaptor.forClass(WishlistItem.class);
        verify(wishlist).save(saved.capture());
        assertEquals(90d, saved.getValue().getTargetPrice());
        assertEquals(Boolean.TRUE, saved.getValue().getAlertsEnabled(), "a target is an explicit alert request");
        verify(analytics).recordAccountActivity("alert_persisted", "u1");

        ResponseEntity<?> rejected = controller.addToWishlist(
                Map.of("productId", "p1", "alertsEnabled", true, "targetPrice", 100d), req);
        assertEquals(400, rejected.getStatusCode().value());
    }

    @Test
    void idempotentTargetReplayDoesNotWriteOrCountAgain() {
        WishlistItem existing = WishlistItem.builder()
                .userId("u1").productId("p1").alertsEnabled(true).targetPrice(90d).build();
        WishlistItemRepository wishlist = mock(WishlistItemRepository.class);
        when(wishlist.findByUserIdAndProductId("u1", "p1")).thenReturn(Optional.of(existing));
        ProductRepository products = mock(ProductRepository.class);
        when(products.findById("p1")).thenReturn(Optional.of(
                Product.builder().id("p1").lowestPrice(100d).build()));
        AnalyticsService analytics = mock(AnalyticsService.class);
        AccountController controller = new AccountController(mock(SavedSearchRepository.class), wishlist,
                products, mock(AnalyticsEventRepository.class),
                mock(PriceAlertNotificationRepository.class), analytics);
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getAttribute("authUserId")).thenReturn("u1");

        ResponseEntity<?> response = controller.addToWishlist(
                Map.of("productId", "p1", "alertsEnabled", true, "targetPrice", 90d), req);

        assertEquals(200, response.getStatusCode().value());
        org.mockito.Mockito.verify(wishlist, org.mockito.Mockito.never()).save(any());
        org.mockito.Mockito.verifyNoInteractions(analytics);
    }

    @Test
    void targetOnlyRequestEnablesAnExistingSavedProduct() {
        WishlistItem existing = WishlistItem.builder()
                .userId("u1").productId("p1").alertsEnabled(false).build();
        WishlistItemRepository wishlist = mock(WishlistItemRepository.class);
        when(wishlist.findByUserIdAndProductId("u1", "p1")).thenReturn(Optional.of(existing));
        when(wishlist.save(existing)).thenReturn(existing);
        ProductRepository products = mock(ProductRepository.class);
        when(products.findById("p1")).thenReturn(Optional.of(
                Product.builder().id("p1").lowestPrice(100d).build()));
        AnalyticsService analytics = mock(AnalyticsService.class);
        AccountController controller = new AccountController(mock(SavedSearchRepository.class), wishlist,
                products, mock(AnalyticsEventRepository.class),
                mock(PriceAlertNotificationRepository.class), analytics);
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getAttribute("authUserId")).thenReturn("u1");

        ResponseEntity<?> response = controller.addToWishlist(
                Map.of("productId", "p1", "targetPrice", 90d), req);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(Boolean.TRUE, existing.getAlertsEnabled());
        assertEquals(90d, existing.getTargetPrice());
        verify(wishlist).save(existing);
        verify(analytics).recordAccountActivity("alert_persisted", "u1");
    }

    @Test
    void duplicateInsertReplayStillAppliesTargetAndEnablesAlert() {
        WishlistItem existing = WishlistItem.builder()
                .userId("u1").productId("p1").alertsEnabled(false).build();
        WishlistItemRepository wishlist = mock(WishlistItemRepository.class);
        when(wishlist.findByUserIdAndProductId("u1", "p1"))
                .thenReturn(Optional.empty(), Optional.of(existing));
        when(wishlist.save(any(WishlistItem.class)))
                .thenThrow(new org.springframework.dao.DuplicateKeyException("concurrent insert"))
                .thenReturn(existing);
        ProductRepository products = mock(ProductRepository.class);
        when(products.findById("p1")).thenReturn(Optional.of(
                Product.builder().id("p1").lowestPrice(100d).build()));
        AnalyticsService analytics = mock(AnalyticsService.class);
        AccountController controller = new AccountController(mock(SavedSearchRepository.class), wishlist,
                products, mock(AnalyticsEventRepository.class),
                mock(PriceAlertNotificationRepository.class), analytics);
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getAttribute("authUserId")).thenReturn("u1");

        ResponseEntity<?> response = controller.addToWishlist(
                Map.of("productId", "p1", "targetPrice", 90d), req);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(Boolean.TRUE, existing.getAlertsEnabled());
        assertEquals(90d, existing.getTargetPrice());
        verify(wishlist, org.mockito.Mockito.times(2)).save(any(WishlistItem.class));
        verify(analytics).recordAccountActivity("alert_persisted", "u1");
    }
}
