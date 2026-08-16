package com.damKemon.dam.kemon.config;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProductionSecretsValidatorTest {
    @Test void rejectsPlaceholderSecretsOnWeb() {
        ProductionSecretsValidator validator = validator(new AppRole("web"), "REPLACE_ME", "a".repeat(32), "owner", "a".repeat(12), "key");
        assertThrows(IllegalStateException.class, () -> validator.run(null));
    }
    @Test void acceptsMinimumRealWebSecretsAndExemptsWorker() {
        assertDoesNotThrow(() -> validator(new AppRole("web"), "a".repeat(32), "b".repeat(32), "owner", "c".repeat(12), "key").run(null));
        assertDoesNotThrow(() -> validator(new AppRole("worker"), "", "", "", "", "").run(null));
    }
    @Test void enabledPaymentsFailClosedWithoutProviderSecrets() {
        ProductionSecretsValidator v = validator(new AppRole("web"), "a".repeat(32), "b".repeat(32), "owner", "c".repeat(12), "key");
        ReflectionTestUtils.setField(v, "paymentsEnabled", true);
        assertThrows(IllegalStateException.class, () -> v.run(null));
    }
    @Test void enabledRewireSandboxRequiresTestKeyAndAcceptsCompleteConfiguration() {
        ProductionSecretsValidator v = validator(new AppRole("web"), "a".repeat(32), "b".repeat(32), "owner", "c".repeat(12), "key");
        ReflectionTestUtils.setField(v, "paymentsEnabled", true);
        ReflectionTestUtils.setField(v, "paymentFingerprintSecret", "f".repeat(32));
        ReflectionTestUtils.setField(v, "lemonTestApiKey", "test-key");
        ReflectionTestUtils.setField(v, "lemonWebhookSecret", "w".repeat(32));
        ReflectionTestUtils.setField(v, "lemonWebhookUrl", "https://damkemon.com/api/payments/v1/webhooks/lemon-squeezy");
        ReflectionTestUtils.setField(v, "rewirePaymentsEnabled", true);
        ReflectionTestUtils.setField(v, "rewireStoreId", 1L);
        ReflectionTestUtils.setField(v, "rewireProductId", 2L);
        ReflectionTestUtils.setField(v, "rewireVariantId", 3L);
        ReflectionTestUtils.setField(v, "rewireTestMode", true);

        assertDoesNotThrow(() -> v.run(null));

        ReflectionTestUtils.setField(v, "lemonTestApiKey", "");
        ReflectionTestUtils.setField(v, "lemonLiveApiKey", "live-key");
        assertThrows(IllegalStateException.class, () -> v.run(null));
    }
    @Test void enabledEchoSandboxRequiresEveryCatalogId() {
        ProductionSecretsValidator v = validator(new AppRole("web"), "a".repeat(32), "b".repeat(32), "owner", "c".repeat(12), "key");
        ReflectionTestUtils.setField(v, "paymentsEnabled", true);
        ReflectionTestUtils.setField(v, "paymentFingerprintSecret", "f".repeat(32));
        ReflectionTestUtils.setField(v, "lemonTestApiKey", "test-key");
        ReflectionTestUtils.setField(v, "lemonWebhookSecret", "w".repeat(32));
        ReflectionTestUtils.setField(v, "lemonWebhookUrl", "https://damkemon.com/api/payments/v1/webhooks/lemon-squeezy");
        ReflectionTestUtils.setField(v, "echoPaymentsEnabled", true);
        ReflectionTestUtils.setField(v, "echoStoreId", 1L);
        ReflectionTestUtils.setField(v, "echoDiamondsProductId", 2L);
        ReflectionTestUtils.setField(v, "echoDiamonds40VariantId", 3L);
        ReflectionTestUtils.setField(v, "echoDiamonds100VariantId", 4L);
        ReflectionTestUtils.setField(v, "echoDiamonds250VariantId", 5L);
        ReflectionTestUtils.setField(v, "echoProProductId", 6L);
        ReflectionTestUtils.setField(v, "echoMonthlyVariantId", 7L);
        ReflectionTestUtils.setField(v, "echoLifetimeVariantId", 8L);
        ReflectionTestUtils.setField(v, "echoTestMode", true);

        assertDoesNotThrow(() -> v.run(null));

        ReflectionTestUtils.setField(v, "echoLifetimeVariantId", 0L);
        assertThrows(IllegalStateException.class, () -> v.run(null));
    }
    private static ProductionSecretsValidator validator(AppRole role, String jwt, String admin, String owner, String password, String resend) {
        ProductionSecretsValidator v = new ProductionSecretsValidator(role);
        ReflectionTestUtils.setField(v, "jwt", jwt); ReflectionTestUtils.setField(v, "adminKey", admin);
        ReflectionTestUtils.setField(v, "ownerUsername", owner); ReflectionTestUtils.setField(v, "ownerPassword", password);
        ReflectionTestUtils.setField(v, "resendKey", resend); return v;
    }
}
