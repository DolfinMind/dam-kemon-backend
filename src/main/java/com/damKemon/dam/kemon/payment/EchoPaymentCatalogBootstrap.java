package com.damKemon.dam.kemon.payment;

import com.damKemon.dam.kemon.payment.model.PaymentApplication;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
@ConditionalOnProperty(name = {"payments.enabled", "payments.echo.enabled"}, havingValue = "true")
public class EchoPaymentCatalogBootstrap implements ApplicationRunner {
    private final PaymentStore store;
    private final PaymentService payments;
    private final long storeId;
    private final long diamondsProductId;
    private final long diamonds40VariantId;
    private final long diamonds100VariantId;
    private final long diamonds250VariantId;
    private final long proProductId;
    private final long monthlyVariantId;
    private final long lifetimeVariantId;
    private final boolean testMode;
    private final String redirectUrl;

    public EchoPaymentCatalogBootstrap(
            PaymentStore store,
            PaymentService payments,
            @Value("${payments.echo.store-id:0}") long storeId,
            @Value("${payments.echo.diamonds-product-id:0}") long diamondsProductId,
            @Value("${payments.echo.diamonds-40-variant-id:0}") long diamonds40VariantId,
            @Value("${payments.echo.diamonds-100-variant-id:0}") long diamonds100VariantId,
            @Value("${payments.echo.diamonds-250-variant-id:0}") long diamonds250VariantId,
            @Value("${payments.echo.pro-product-id:0}") long proProductId,
            @Value("${payments.echo.monthly-variant-id:0}") long monthlyVariantId,
            @Value("${payments.echo.lifetime-variant-id:0}") long lifetimeVariantId,
            @Value("${payments.echo.test-mode:true}") boolean testMode,
            @Value("${payments.echo.redirect-url:}") String redirectUrl) {
        this.store = store;
        this.payments = payments;
        this.storeId = storeId;
        this.diamondsProductId = diamondsProductId;
        this.diamonds40VariantId = diamonds40VariantId;
        this.diamonds100VariantId = diamonds100VariantId;
        this.diamonds250VariantId = diamonds250VariantId;
        this.proProductId = proProductId;
        this.monthlyVariantId = monthlyVariantId;
        this.lifetimeVariantId = lifetimeVariantId;
        this.testMode = testMode;
        this.redirectUrl = redirectUrl;
    }

    @Override
    public void run(ApplicationArguments args) {
        Instant now = Instant.now();
        PaymentApplication app = store.application("echo-memory").orElseGet(() -> PaymentApplication.builder()
                .appId("echo-memory").displayName("Echo Memory").active(true).acceptDamkemonJwt(false)
                .publicCheckout(true).publicLicense(true).createdAt(now).build());
        app.setUpdatedAt(now);
        store.save(app);

        payments.upsertProduct("echo-memory", "diamonds_40", "echo_diamonds_40", storeId,
                diamondsProductId, diamonds40VariantId, false, null, 0, false, testMode, true, redirectUrl);
        payments.upsertProduct("echo-memory", "diamonds_100", "echo_diamonds_100", storeId,
                diamondsProductId, diamonds100VariantId, false, null, 0, false, testMode, true, redirectUrl);
        payments.upsertProduct("echo-memory", "diamonds_250", "echo_diamonds_250", storeId,
                diamondsProductId, diamonds250VariantId, false, null, 0, false, testMode, true, redirectUrl);
        payments.upsertProduct("echo-memory", "pro_monthly", "echo_pro", storeId,
                proProductId, monthlyVariantId, true, "month", 1, true, testMode, true, redirectUrl);
        payments.upsertProduct("echo-memory", "lifetime", "echo_pro", storeId,
                proProductId, lifetimeVariantId, false, null, 0, true, testMode, true, redirectUrl);
    }
}
