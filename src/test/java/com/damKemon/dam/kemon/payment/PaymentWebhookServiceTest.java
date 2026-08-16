package com.damKemon.dam.kemon.payment;

import com.damKemon.dam.kemon.payment.model.PaymentCheckout;
import com.damKemon.dam.kemon.payment.model.PaymentEntitlement;
import com.damKemon.dam.kemon.payment.model.PaymentOrder;
import com.damKemon.dam.kemon.payment.model.PaymentProduct;
import com.damKemon.dam.kemon.payment.model.PaymentSubscription;
import com.damKemon.dam.kemon.payment.model.PaymentWebhookEvent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PaymentWebhookServiceTest {
    @Test void signedOrderCreatesAuthoritativePaidRecordForMappedCheckout() throws Exception {
        String secret = "w".repeat(32);
        PaymentStore store = mock(PaymentStore.class);
        PaymentSecurity security = new PaymentSecurity("f".repeat(32), secret);
        PaymentWebhookService service = new PaymentWebhookService(store, security);
        PaymentCheckout checkout = PaymentCheckout.builder().id("checkout-1").appId("rewire")
                .productCode("lifetime").subjectType("installation").subjectId("opaque-subject").testMode(true).build();
        PaymentProduct product = PaymentProduct.builder().appId("rewire").code("lifetime")
                .storeId(445309).productId(1266751).variantId(1980706).testMode(true).active(true).build();
        when(store.checkout("checkout-1")).thenReturn(Optional.of(checkout));
        when(store.product("rewire", "lifetime")).thenReturn(Optional.of(product));
        when(store.order("lemon_squeezy", "42")).thenReturn(Optional.empty());
        when(store.insert(any(PaymentWebhookEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(store.save(any(PaymentWebhookEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(store.save(any(PaymentOrder.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(store.save(any(PaymentCheckout.class))).thenAnswer(invocation -> invocation.getArgument(0));

        byte[] payload = ("{\"meta\":{\"event_name\":\"order_created\",\"custom_data\":{\"payment_checkout_id\":\"checkout-1\"}},"
                + "\"data\":{\"type\":\"orders\",\"id\":\"42\",\"attributes\":{\"store_id\":445309,\"customer_id\":8,"
                + "\"status\":\"paid\",\"total\":349900,\"currency\":\"BDT\",\"test_mode\":true,"
                + "\"first_order_item\":{\"product_id\":1266751,\"variant_id\":1980706}}}}").getBytes(StandardCharsets.UTF_8);

        PaymentWebhookService.WebhookResult result = service.process(payload, hmac(secret, payload), "order_created");

        assertEquals("processed", result.status());
        ArgumentCaptor<PaymentOrder> saved = ArgumentCaptor.forClass(PaymentOrder.class);
        verify(store).save(saved.capture());
        assertEquals("paid", saved.getValue().getStatus());
        assertEquals("opaque-subject", saved.getValue().getSubjectId());
        assertEquals(349900, saved.getValue().getTotal());
        ArgumentCaptor<PaymentWebhookEvent> event = ArgumentCaptor.forClass(PaymentWebhookEvent.class);
        verify(store, atLeastOnce()).save(event.capture());
        assertEquals("rewire", event.getValue().getAppId());
        assertEquals(true, event.getValue().getTestMode());
    }

    @Test void signedSubscriptionCreatesDirectEntitlementForAccountPlan() throws Exception {
        String secret = "w".repeat(32);
        PaymentStore store = mock(PaymentStore.class);
        PaymentWebhookService service = new PaymentWebhookService(store, new PaymentSecurity("f".repeat(32), secret));
        PaymentCheckout checkout = PaymentCheckout.builder().id("checkout-2").appId("damkemon")
                .productCode("monthly").subjectType("damkemon_user").subjectId("user:7").testMode(true).build();
        PaymentProduct product = PaymentProduct.builder().appId("damkemon").code("monthly")
                .entitlementCode("damkemon_pro").storeId(445309).productId(200).variantId(201)
                .subscription(true).billingInterval("month").billingIntervalCount(1)
                .licenseRequired(false).testMode(true).active(true).build();
        when(store.checkout("checkout-2")).thenReturn(Optional.of(checkout));
        when(store.product("damkemon", "monthly")).thenReturn(Optional.of(product));
        when(store.subscription("lemon_squeezy", "88")).thenReturn(Optional.empty());
        when(store.entitlementBySubscription("88")).thenReturn(Optional.empty());
        when(store.insert(any(PaymentWebhookEvent.class))).thenAnswer(call -> call.getArgument(0));
        when(store.save(any(PaymentWebhookEvent.class))).thenAnswer(call -> call.getArgument(0));
        when(store.save(any(PaymentSubscription.class))).thenAnswer(call -> call.getArgument(0));
        when(store.save(any(PaymentEntitlement.class))).thenAnswer(call -> call.getArgument(0));

        byte[] payload = ("{\"meta\":{\"event_name\":\"subscription_created\",\"custom_data\":{\"payment_checkout_id\":\"checkout-2\"}},"
                + "\"data\":{\"type\":\"subscriptions\",\"id\":\"88\",\"attributes\":{\"store_id\":445309,"
                + "\"order_id\":77,\"product_id\":200,\"variant_id\":201,\"status\":\"active\","
                + "\"cancelled\":false,\"renews_at\":\"2026-09-09T00:00:00Z\",\"test_mode\":true}}}")
                .getBytes(StandardCharsets.UTF_8);

        assertEquals("processed", service.process(payload, hmac(secret, payload), "subscription_created").status());
        ArgumentCaptor<PaymentSubscription> subscription = ArgumentCaptor.forClass(PaymentSubscription.class);
        verify(store).save(subscription.capture());
        assertEquals("active", subscription.getValue().getStatus());
        ArgumentCaptor<PaymentEntitlement> entitlement = ArgumentCaptor.forClass(PaymentEntitlement.class);
        verify(store).save(entitlement.capture());
        assertEquals("ACTIVE", entitlement.getValue().getStatus());
        assertEquals("88", entitlement.getValue().getProviderSubscriptionId());
        assertEquals("damkemon_pro", entitlement.getValue().getEntitlementCode());
    }

    @Test void subscriptionExpiryRevokesLicenseEntitlementsWithoutRemovingLifetimeSupport() throws Exception {
        String secret = "w".repeat(32);
        PaymentStore store = mock(PaymentStore.class);
        PaymentWebhookService service = new PaymentWebhookService(store, new PaymentSecurity("f".repeat(32), secret));
        PaymentCheckout checkout = PaymentCheckout.builder().id("checkout-3").appId("rewire")
                .productCode("yearly").subjectType("installation").subjectId("opaque").testMode(true).build();
        PaymentProduct product = PaymentProduct.builder().appId("rewire").code("yearly")
                .entitlementCode("rewire_pro").storeId(445309).productId(300).variantId(301)
                .subscription(true).billingInterval("year").billingIntervalCount(1)
                .licenseRequired(true).testMode(true).active(true).build();
        PaymentSubscription existing = PaymentSubscription.builder().id("lemon_squeezy:99")
                .provider("lemon_squeezy").providerSubscriptionId("99").checkoutId("checkout-3").build();
        PaymentEntitlement entitlement = PaymentEntitlement.builder().id("ent-3").status("ACTIVE").build();
        when(store.checkout("checkout-3")).thenReturn(Optional.of(checkout));
        when(store.product("rewire", "yearly")).thenReturn(Optional.of(product));
        when(store.subscription("lemon_squeezy", "99")).thenReturn(Optional.of(existing));
        when(store.entitlementsByCheckout("checkout-3")).thenReturn(java.util.List.of(entitlement));
        when(store.insert(any(PaymentWebhookEvent.class))).thenAnswer(call -> call.getArgument(0));
        when(store.save(any(PaymentWebhookEvent.class))).thenAnswer(call -> call.getArgument(0));
        when(store.save(any(PaymentSubscription.class))).thenAnswer(call -> call.getArgument(0));
        when(store.save(any(PaymentEntitlement.class))).thenAnswer(call -> call.getArgument(0));

        byte[] payload = ("{\"meta\":{\"event_name\":\"subscription_updated\",\"custom_data\":{\"payment_checkout_id\":\"checkout-3\"}},"
                + "\"data\":{\"type\":\"subscriptions\",\"id\":\"99\",\"attributes\":{\"store_id\":445309,"
                + "\"order_id\":98,\"product_id\":300,\"variant_id\":301,\"status\":\"expired\","
                + "\"cancelled\":true,\"ends_at\":\"2026-08-09T00:00:00Z\",\"test_mode\":true}}}")
                .getBytes(StandardCharsets.UTF_8);

        service.process(payload, hmac(secret, payload), "subscription_updated");

        assertEquals("REVOKED", entitlement.getStatus());
    }

    @Test void activeSubscriptionUpdateRestoresDirectEntitlement() throws Exception {
        String secret = "w".repeat(32);
        PaymentStore store = mock(PaymentStore.class);
        PaymentWebhookService service = new PaymentWebhookService(store, new PaymentSecurity("f".repeat(32), secret));
        PaymentCheckout checkout = PaymentCheckout.builder().id("checkout-4").appId("damkemon")
                .productCode("monthly").subjectType("damkemon_user").subjectId("user:8").testMode(true).build();
        PaymentProduct product = PaymentProduct.builder().appId("damkemon").code("monthly")
                .entitlementCode("damkemon_pro").storeId(445309).productId(400).variantId(401)
                .subscription(true).billingInterval("month").billingIntervalCount(1)
                .licenseRequired(false).testMode(true).active(true).build();
        PaymentSubscription subscription = PaymentSubscription.builder().id("lemon_squeezy:100")
                .provider("lemon_squeezy").providerSubscriptionId("100").checkoutId("checkout-4").build();
        PaymentEntitlement entitlement = PaymentEntitlement.builder().id("ent-4").status("REVOKED").build();
        when(store.checkout("checkout-4")).thenReturn(Optional.of(checkout));
        when(store.product("damkemon", "monthly")).thenReturn(Optional.of(product));
        when(store.subscription("lemon_squeezy", "100")).thenReturn(Optional.of(subscription));
        when(store.entitlementBySubscription("100")).thenReturn(Optional.of(entitlement));
        when(store.insert(any(PaymentWebhookEvent.class))).thenAnswer(call -> call.getArgument(0));
        when(store.save(any(PaymentWebhookEvent.class))).thenAnswer(call -> call.getArgument(0));
        when(store.save(any(PaymentSubscription.class))).thenAnswer(call -> call.getArgument(0));
        when(store.save(any(PaymentEntitlement.class))).thenAnswer(call -> call.getArgument(0));

        byte[] payload = ("{\"meta\":{\"event_name\":\"subscription_updated\",\"custom_data\":{\"payment_checkout_id\":\"checkout-4\"}},"
                + "\"data\":{\"type\":\"subscriptions\",\"id\":\"100\",\"attributes\":{\"store_id\":445309,"
                + "\"order_id\":101,\"product_id\":400,\"variant_id\":401,\"status\":\"active\","
                + "\"cancelled\":false,\"renews_at\":\"2026-09-09T00:00:00Z\",\"test_mode\":true}}}")
                .getBytes(StandardCharsets.UTF_8);

        service.process(payload, hmac(secret, payload), "subscription_updated");

        assertEquals("ACTIVE", entitlement.getStatus());
    }

    private static String hmac(String secret, byte[] payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(payload));
    }
}
