package com.damKemon.dam.kemon.payment;

import com.damKemon.dam.kemon.payment.model.PaymentAdminAction;
import com.damKemon.dam.kemon.payment.model.PaymentEntitlement;
import com.damKemon.dam.kemon.payment.model.PaymentOrder;
import com.damKemon.dam.kemon.payment.model.PaymentProduct;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaymentProviderAdminServiceTest {
    private final ObjectMapper json = new ObjectMapper();
    private PaymentStore store;
    private LemonSqueezyClient lemon;
    private PaymentProviderAdminService service;
    private PaymentOrder order;

    @BeforeEach void setUp() {
        store = mock(PaymentStore.class);
        lemon = mock(LemonSqueezyClient.class);
        service = new PaymentProviderAdminService(store, lemon,
                "https://damkemon.com/api/payments/v1/webhooks/lemon-squeezy", "w".repeat(32));
        order = PaymentOrder.builder().provider("lemon_squeezy").providerOrderId("42")
                .checkoutId("checkout-1").appId("rewire").productCode("lifetime")
                .status("paid").total(349900).currency("BDT").testMode(true).build();
        PaymentProduct product = PaymentProduct.builder().appId("rewire").code("lifetime")
                .storeId(445309).productId(1266751).variantId(1980706).testMode(true).build();
        when(store.order("lemon_squeezy", "42")).thenReturn(Optional.of(order));
        when(store.product("rewire", "lifetime")).thenReturn(Optional.of(product));
        when(store.save(any(PaymentAdminAction.class))).thenAnswer(call -> call.getArgument(0));
        when(store.save(any(PaymentOrder.class))).thenAnswer(call -> call.getArgument(0));
    }

    @Test void reportsMissingModeSpecificApiKeyWithoutCallingProvider() {
        when(lemon.isConfigured(true)).thenReturn(false);

        PaymentProviderAdminService.ProviderStatus status = service.status(true);

        assertFalse(status.configured());
        assertEquals("api_key_missing", status.errorCode());
        verify(lemon, never()).currentUser(true);
    }

    @Test void refundRequiresExactProviderOrderConfirmationBeforeMutation() {
        PaymentException error = assertThrows(PaymentException.class,
                () -> service.refund("42", null, "wrong", "user:admin"));

        assertEquals("refund_confirmation_mismatch", error.code());
        verify(lemon, never()).refundOrder(any(), any(), any(Boolean.class));
        verify(store, never()).save(any(PaymentAdminAction.class));
    }

    @Test void fullRefundUpdatesLocalOrderRevokesEntitlementAndWritesAudit() throws Exception {
        PaymentEntitlement entitlement = PaymentEntitlement.builder().id("ent-1").status("ACTIVE").build();
        when(store.entitlementsByCheckout("checkout-1")).thenReturn(List.of(entitlement));
        when(store.save(any(PaymentEntitlement.class))).thenAnswer(call -> call.getArgument(0));
        when(lemon.refundOrder("42", null, true)).thenReturn(json.readTree("""
                {"data":{"type":"orders","id":"42","attributes":{"store_id":445309,
                "customer_id":8,"status":"refunded","subtotal":349900,"discount_total":0,"tax":0,
                "total":349900,"refunded_amount":349900,"currency":"BDT","test_mode":true,
                "first_order_item":{"product_id":1266751,"variant_id":1980706},
                "created_at":"2026-08-08T00:00:00Z","updated_at":"2026-08-08T00:01:00Z"}}}
                """));

        PaymentProviderAdminService.ProviderOrder result = service.refund("42", null, "42", "user:admin");

        assertEquals(349900, result.refundedAmount());
        assertEquals("refunded", order.getStatus());
        assertEquals("REFUNDED", entitlement.getStatus());
        verify(store, org.mockito.Mockito.times(2)).save(any(PaymentAdminAction.class));
    }
}
