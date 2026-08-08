package com.damKemon.dam.kemon.payment;

import com.damKemon.dam.kemon.payment.model.PaymentCheckout;
import com.damKemon.dam.kemon.payment.model.PaymentOrder;
import com.damKemon.dam.kemon.payment.model.PaymentProduct;
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

    private static String hmac(String secret, byte[] payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(payload));
    }
}
