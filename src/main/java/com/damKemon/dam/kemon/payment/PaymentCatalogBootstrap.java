package com.damKemon.dam.kemon.payment;

import com.damKemon.dam.kemon.payment.model.PaymentApplication;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
@ConditionalOnProperty(name = {"payments.enabled", "payments.rewire.enabled"}, havingValue = "true")
public class PaymentCatalogBootstrap implements ApplicationRunner {
    private final PaymentStore store;
    private final PaymentService payments;
    private final long storeId;
    private final long productId;
    private final long variantId;
    private final boolean testMode;
    private final String redirectUrl;

    public PaymentCatalogBootstrap(PaymentStore store, PaymentService payments,
                                   @Value("${payments.rewire.store-id:0}") long storeId,
                                   @Value("${payments.rewire.product-id:0}") long productId,
                                   @Value("${payments.rewire.variant-id:0}") long variantId,
                                   @Value("${payments.rewire.test-mode:true}") boolean testMode,
                                   @Value("${payments.rewire.redirect-url:}") String redirectUrl) {
        this.store = store;
        this.payments = payments;
        this.storeId = storeId;
        this.productId = productId;
        this.variantId = variantId;
        this.testMode = testMode;
        this.redirectUrl = redirectUrl;
    }

    @Override
    public void run(ApplicationArguments args) {
        Instant now = Instant.now();
        PaymentApplication app = store.application("rewire").orElseGet(() -> PaymentApplication.builder()
                .appId("rewire").displayName("Rewire").active(true).acceptDamkemonJwt(false)
                .publicCheckout(true).publicLicense(true).createdAt(now).build());
        app.setUpdatedAt(now);
        store.save(app);
        payments.upsertProduct("rewire", "lifetime", "rewire_pro", storeId, productId, variantId,
                testMode, true, redirectUrl);
    }
}
