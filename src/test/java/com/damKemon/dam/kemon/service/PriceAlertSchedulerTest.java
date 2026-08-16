package com.damKemon.dam.kemon.service;

import com.damKemon.dam.kemon.config.AppRole;
import com.damKemon.dam.kemon.model.Product;
import com.damKemon.dam.kemon.model.User;
import com.damKemon.dam.kemon.model.WishlistItem;
import com.damKemon.dam.kemon.model.PriceAlertNotification;
import com.damKemon.dam.kemon.repository.PriceAlertNotificationRepository;
import com.damKemon.dam.kemon.repository.ProductRepository;
import com.damKemon.dam.kemon.repository.UserRepository;
import com.damKemon.dam.kemon.repository.WishlistItemRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.*;

class PriceAlertSchedulerTest {
    @Test void killSwitchDoesNotScan() {
        WishlistItemRepository wishlist = mock(WishlistItemRepository.class);
        PriceAlertScheduler s = scheduler(wishlist, mock(ProductRepository.class), mock(UserRepository.class), mock(PriceAlertNotificationRepository.class), mock(EmailNotifier.class));
        ReflectionTestUtils.setField(s, "enabled", false); s.runAlertScan(); verifyNoInteractions(wishlist);
    }
    @Test void staleAndUnverifiedRowsDoNotCreateOrSendAlerts() {
        WishlistItemRepository wishlist = mock(WishlistItemRepository.class); ProductRepository products = mock(ProductRepository.class);
        UserRepository users = mock(UserRepository.class); PriceAlertNotificationRepository notes = mock(PriceAlertNotificationRepository.class); EmailNotifier notifier = mock(EmailNotifier.class);
        PriceAlertScheduler s = scheduler(wishlist, products, users, notes, notifier);
        WishlistItem row = WishlistItem.builder().userId("u").productId("p").alertsEnabled(true).targetPrice(100d).build();
        when(products.findById("p")).thenReturn(Optional.of(Product.builder().id("p").lowestPrice(90d).lastScraped(LocalDateTime.now().minusDays(2)).build()));
        assertFalse((Boolean) ReflectionTestUtils.invokeMethod(s, "processOne", row)); verify(notes, never()).save(any()); verifyNoInteractions(notifier);
        when(products.findById("p")).thenReturn(Optional.of(Product.builder().id("p").lowestPrice(90d).lastScraped(LocalDateTime.now()).build()));
        when(users.findById("u")).thenReturn(Optional.of(User.builder().email("u@test").emailVerified(false).build()));
        assertFalse((Boolean) ReflectionTestUtils.invokeMethod(s, "processOne", row)); verify(notes, never()).save(any()); verifyNoInteractions(notifier);
    }
    @Test void failedImmediateSendPersistsRetryWithoutDebouncingWishlist() {
        WishlistItemRepository w = mock(WishlistItemRepository.class); ProductRepository p = mock(ProductRepository.class); UserRepository u = mock(UserRepository.class); PriceAlertNotificationRepository n = mock(PriceAlertNotificationRepository.class); EmailNotifier e = mock(EmailNotifier.class);
        PriceAlertScheduler s = scheduler(w,p,u,n,e); WishlistItem row = WishlistItem.builder().userId("u").productId("p").alertsEnabled(true).targetPrice(100d).notifyChannel("email").build();
        when(p.findById("p")).thenReturn(Optional.of(Product.builder().id("p").lowestPrice(90d).lastScraped(LocalDateTime.now()).build())); when(u.findById("u")).thenReturn(Optional.of(User.builder().email("u@test").emailVerified(true).build()));
        when(n.findByUserIdAndProductIdAndCreatedAtAfter(any(),any(),any())).thenReturn(List.of()); when(n.save(any())).thenAnswer(i -> i.getArgument(0)); when(e.sendPriceDropAlert(any(),any(),any(),anyDouble(),any())).thenReturn(false);
        assertFalse((Boolean) ReflectionTestUtils.invokeMethod(s,"processOne",row));
        var cap = org.mockito.ArgumentCaptor.forClass(PriceAlertNotification.class); verify(n, times(2)).save(cap.capture()); PriceAlertNotification failed = cap.getAllValues().get(1);
        org.junit.jupiter.api.Assertions.assertEquals("failed", failed.getDeliveryState()); org.junit.jupiter.api.Assertions.assertEquals("failed", failed.getSentVia()); org.junit.jupiter.api.Assertions.assertEquals(1, failed.getDeliveryAttempts()); org.junit.jupiter.api.Assertions.assertNotNull(failed.getNextDeliveryAttemptAt()); verify(w, never()).save(any());
    }
    @Test void retrySuccessAndTerminalAndSkippedArePersisted() {
        WishlistItemRepository w = mock(WishlistItemRepository.class); ProductRepository p = mock(ProductRepository.class); UserRepository u = mock(UserRepository.class); PriceAlertNotificationRepository n = mock(PriceAlertNotificationRepository.class); EmailNotifier e = mock(EmailNotifier.class); PriceAlertScheduler s = scheduler(w,p,u,n,e);
        PriceAlertNotification due = PriceAlertNotification.builder().userId("u").productId("p").currentPrice(90d).reason("hit_target").deliveryKey("k").deliveryState("failed").deliveryAttempts(1).build(); WishlistItem row = WishlistItem.builder().userId("u").productId("p").alertsEnabled(true).notifyChannel("email").targetPrice(100d).build();
        when(n.findTop100ByDeliveryStateInAndNextDeliveryAttemptAtLessThanEqualOrderByCreatedAtAsc(any(),any())).thenReturn(List.of(due)); when(u.findById("u")).thenReturn(Optional.of(User.builder().email("u@test").emailVerified(true).build())); when(p.findById("p")).thenReturn(Optional.of(Product.builder().id("p").lowestPrice(90d).lastScraped(LocalDateTime.now()).build())); when(w.findByUserIdAndProductId("u","p")).thenReturn(Optional.of(row)); when(e.sendPriceDropAlert(any(),any(),any(),anyDouble(),any())).thenReturn(true);
        ReflectionTestUtils.invokeMethod(s,"retryDueAlerts"); org.junit.jupiter.api.Assertions.assertEquals("accepted",due.getDeliveryState()); org.junit.jupiter.api.Assertions.assertNull(due.getNextDeliveryAttemptAt());
        due.setDeliveryAttempts(3); due.setDeliveryState("failed"); due.setNextDeliveryAttemptAt(LocalDateTime.now()); clearInvocations(e); ReflectionTestUtils.invokeMethod(s,"retryDueAlerts"); org.junit.jupiter.api.Assertions.assertEquals("failed_terminal",due.getDeliveryState()); verifyNoInteractions(e);
        due.setDeliveryAttempts(1); due.setDeliveryState("failed"); row.setAlertsEnabled(false); ReflectionTestUtils.invokeMethod(s,"retryDueAlerts"); org.junit.jupiter.api.Assertions.assertEquals("skipped",due.getDeliveryState());
    }
    private static PriceAlertScheduler scheduler(WishlistItemRepository w, ProductRepository p, UserRepository u, PriceAlertNotificationRepository n, EmailNotifier e) {
        PriceAlertScheduler s = new PriceAlertScheduler(w,p,u,n,e,new AppRole("web"));
        ReflectionTestUtils.setField(s,"enabled",true); ReflectionTestUtils.setField(s,"maxAgeHours",30); return s;
    }
}
