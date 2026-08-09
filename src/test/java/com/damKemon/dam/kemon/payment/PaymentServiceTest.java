package com.damKemon.dam.kemon.payment;

import com.damKemon.dam.kemon.payment.model.PaymentApplication;
import com.damKemon.dam.kemon.payment.model.PaymentCheckout;
import com.damKemon.dam.kemon.payment.model.PaymentEntitlement;
import com.damKemon.dam.kemon.payment.model.PaymentLicense;
import com.damKemon.dam.kemon.payment.model.PaymentOrder;
import com.damKemon.dam.kemon.payment.model.PaymentProduct;
import com.damKemon.dam.kemon.payment.model.PaymentSubscription;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

import java.util.Optional;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PaymentServiceTest {
    private final ObjectMapper json = new ObjectMapper();
    private PaymentStore store;
    private LemonSqueezyClient lemon;
    private PaymentSecurity security;
    private PaymentService service;
    private PaymentApplication app;
    private PaymentProduct product;
    private String installationId;
    private String subjectId;

    @BeforeEach void setUp() {
        store = mock(PaymentStore.class);
        lemon = mock(LemonSqueezyClient.class);
        security = new PaymentSecurity("f".repeat(32), "w".repeat(32));
        service = new PaymentService(store, security, lemon);
        app = PaymentApplication.builder().appId("rewire").active(true).publicLicense(true).publicCheckout(true).build();
        product = PaymentProduct.builder().appId("rewire").code("lifetime").entitlementCode("rewire_pro")
                .storeId(445309).productId(1266751).variantId(1980706).testMode(true).active(true).build();
        installationId = "installation_abcdefghijklmnopqrstuvwxyz";
        subjectId = security.subjectFingerprint("installation", "rewire:" + installationId);
        when(store.application("rewire")).thenReturn(Optional.of(app));
        when(store.product("rewire", "lifetime")).thenReturn(Optional.of(product));
    }

    @Test void activatesOnlyAfterSignedOrderOwnershipWasSynchronized() throws Exception {
        PaymentOrder order = PaymentOrder.builder().providerOrderId("42").checkoutId("checkout-1")
                .appId("rewire").productCode("lifetime").subjectId(subjectId).status("paid").build();
        when(store.order("lemon_squeezy", "42")).thenReturn(Optional.of(order));
        when(lemon.validateLicense("license-secret", null)).thenReturn(licenseJson(false));
        when(lemon.activateLicense("license-secret", "Rewire Android")).thenReturn(licenseJson(true));
        when(store.license("lemon_squeezy", "77")).thenReturn(Optional.empty());
        when(store.entitlement("rewire", subjectId, "installation_abcdefghijklmnopqrstuvwxyz"))
                .thenReturn(Optional.empty());
        when(store.save(any(PaymentLicense.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(store.save(any(PaymentEntitlement.class))).thenAnswer(invocation -> invocation.getArgument(0));

        PaymentService.LicenseResult result = service.activate("rewire", "lifetime", caller(),
                "license-secret", "Rewire Android");

        assertTrue(result.valid());
        assertEquals("rewire_pro", result.entitlementCode());
        ArgumentCaptor<PaymentLicense> license = ArgumentCaptor.forClass(PaymentLicense.class);
        verify(store).save(license.capture());
        assertNotEquals("license-secret", license.getValue().getLicenseKeyFingerprint());
        assertEquals("lice…cret", license.getValue().getKeyShort());
    }

    @Test void retriesAnExistingFailedCheckoutWithoutCreatingAnotherRecord() throws Exception {
        PaymentCheckout failed = PaymentCheckout.builder().id("checkout-1").appId("rewire")
                .productCode("lifetime").entitlementCode("rewire_pro").subjectType("installation")
                .subjectId(subjectId).idempotencyKey("checkout_retry_12345678").status("FAILED").testMode(true)
                .expiresAt(Instant.EPOCH).createdAt(Instant.EPOCH).updatedAt(Instant.EPOCH).build();
        when(store.checkoutByIdempotency("rewire", subjectId, "checkout_retry_12345678")).thenReturn(Optional.of(failed));
        when(lemon.createCheckout(eq(product), eq("checkout-1"), isNull(), any())).thenReturn(json.readTree("""
                {"data":{"id":"provider-checkout","attributes":{"url":"https://example.test/checkout"}}}
                """));

        PaymentService.CheckoutResult result = service.createCheckout("rewire", "lifetime", "checkout_retry_12345678", caller(), null);

        assertEquals("checkout-1", result.checkoutId());
        assertEquals("OPEN", result.status());
        assertEquals("https://example.test/checkout", result.checkoutUrl());
        verify(store, never()).insert(any(PaymentCheckout.class));
        verify(lemon).createCheckout(eq(product), eq("checkout-1"), isNull(), any());
        verify(store, times(2)).save(failed);
    }

    @Test void rejectsAValidLicenseOwnedByAnotherTrustedBackendSubject() throws Exception {
        String apiKey = "pay_sk_partner_" + "a".repeat(43);
        app.setPublicLicense(false);
        app.setApiKeySha256(security.apiKeyDigest(apiKey));
        PaymentOrder stolen = PaymentOrder.builder().providerOrderId("42").checkoutId("checkout-1")
                .appId("rewire").productCode("lifetime").subjectId("external:someone-else").status("paid").build();
        when(store.order("lemon_squeezy", "42")).thenReturn(Optional.of(stolen));
        when(lemon.validateLicense("license-secret", null)).thenReturn(licenseJson(false));

        PaymentException error = assertThrows(PaymentException.class, () -> service.activate(
                "rewire", "lifetime", new PaymentService.Caller(null, null, apiKey, "buyer-a", null),
                "license-secret", "Partner Website"));

        assertEquals(HttpStatus.FORBIDDEN, error.status());
        assertEquals("license_not_owned", error.code());
        verify(lemon, never()).activateLicense(any(), any());
        verify(store, never()).save(any(PaymentEntitlement.class));
    }

    @Test void publicBearerLicenseCanUseAnotherInstallationWithinProviderLimit() throws Exception {
        String secondInstallation = "installation_second_abcdefghijklmnop";
        String secondSubject = security.subjectFingerprint("installation", "rewire:" + secondInstallation);
        PaymentOrder order = PaymentOrder.builder().providerOrderId("42").checkoutId("checkout-1")
                .appId("rewire").productCode("lifetime").subjectId(subjectId).status("paid").build();
        PaymentLicense known = PaymentLicense.builder().id("lemon_squeezy:77").provider("lemon_squeezy")
                .providerLicenseId("77").providerOrderId("42").checkoutId("checkout-1")
                .appId("rewire").productCode("lifetime")
                .licenseKeyFingerprint(security.licenseFingerprint("license-secret")).build();
        when(store.order("lemon_squeezy", "42")).thenReturn(Optional.of(order));
        when(store.licenseByFingerprint("rewire", security.licenseFingerprint("license-secret")))
                .thenReturn(Optional.of(known));
        when(lemon.validateLicense("license-secret", null)).thenReturn(licenseJson(false));
        when(lemon.activateLicense("license-secret", "Second Android")).thenReturn(licenseJson(true));
        when(store.license("lemon_squeezy", "77")).thenReturn(Optional.of(known));
        when(store.entitlement("rewire", secondSubject, "installation_abcdefghijklmnopqrstuvwxyz"))
                .thenReturn(Optional.empty());
        when(store.save(any(PaymentLicense.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(store.save(any(PaymentEntitlement.class))).thenAnswer(invocation -> invocation.getArgument(0));

        PaymentService.LicenseResult result = service.activate("rewire", "lifetime",
                new PaymentService.Caller(null, null, null, null, secondInstallation),
                "license-secret", "Second Android");

        assertTrue(result.valid());
        verify(lemon).activateLicense("license-secret", "Second Android");
    }

    @Test void validatesDirectSubscriptionEntitlementWithoutAProviderSecretInTheClient() {
        product.setCode("monthly");
        product.setSubscription(true);
        product.setBillingInterval("month");
        product.setBillingIntervalCount(1);
        product.setLicenseRequired(false);
        when(store.product("rewire", "monthly")).thenReturn(Optional.of(product));
        PaymentEntitlement entitlement = PaymentEntitlement.builder().id("ent-1").appId("rewire")
                .productCode("monthly").entitlementCode("rewire_pro").subjectId(subjectId)
                .providerSubscriptionId("88").status("ACTIVE").testMode(true)
                .expiresAt(Instant.now().plusSeconds(3600)).build();
        PaymentSubscription subscription = PaymentSubscription.builder().providerSubscriptionId("88")
                .renewsAt(Instant.now().plusSeconds(1800)).build();
        when(store.entitlementByProduct("rewire", subjectId, "monthly")).thenReturn(Optional.of(entitlement));
        when(store.subscription("lemon_squeezy", "88")).thenReturn(Optional.of(subscription));

        PaymentService.EntitlementResult result = service.validateEntitlement("rewire", "monthly", caller());

        assertTrue(result.valid());
        assertEquals("rewire_pro", result.entitlementCode());
        assertNotNull(result.renewsAt());
        verifyNoInteractions(lemon);
    }

    private PaymentService.Caller caller() {
        return new PaymentService.Caller(null, null, null, null, installationId);
    }

    private JsonNode licenseJson(boolean activated) throws Exception {
        String instance = activated ? "\"instance\":{\"id\":\"installation_abcdefghijklmnopqrstuvwxyz\"}," : "";
        String flag = activated ? "\"activated\":true," : "\"valid\":true,";
        return json.readTree("{" + flag + instance
                + "\"license_key\":{\"id\":77,\"status\":\"active\",\"activation_limit\":3,\"activation_usage\":1,\"test_mode\":true},"
                + "\"meta\":{\"store_id\":445309,\"product_id\":1266751,\"variant_id\":1980706,\"order_id\":42}}");
    }
}
