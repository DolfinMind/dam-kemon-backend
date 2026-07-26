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
    private static ProductionSecretsValidator validator(AppRole role, String jwt, String admin, String owner, String password, String resend) {
        ProductionSecretsValidator v = new ProductionSecretsValidator(role);
        ReflectionTestUtils.setField(v, "jwt", jwt); ReflectionTestUtils.setField(v, "adminKey", admin);
        ReflectionTestUtils.setField(v, "ownerUsername", owner); ReflectionTestUtils.setField(v, "ownerPassword", password);
        ReflectionTestUtils.setField(v, "resendKey", resend); return v;
    }
}
