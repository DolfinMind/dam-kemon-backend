package com.damKemon.dam.kemon.payment;

import com.damKemon.dam.kemon.payment.model.PaymentApplication;
import com.damKemon.dam.kemon.payment.model.PaymentProduct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
@ConditionalOnProperty(name = {"payments.enabled", "payments.rewire.enabled"}, havingValue = "true")
public class PaymentCatalogBootstrap implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(PaymentCatalogBootstrap.class);

    private final PaymentStore store;
    private final PaymentService payments;
    private final PaymentProviderAdminService provider;
    private final long storeId;
    private final long productId;
    private final long variantId;
    private final boolean testMode;
    private final String redirectUrl;

    public PaymentCatalogBootstrap(PaymentStore store, PaymentService payments, PaymentProviderAdminService provider,
                                   @Value("${payments.rewire.store-id:0}") long storeId,
                                   @Value("${payments.rewire.product-id:0}") long productId,
                                   @Value("${payments.rewire.variant-id:0}") long variantId,
                                   @Value("${payments.rewire.test-mode:true}") boolean testMode,
                                   @Value("${payments.rewire.redirect-url:}") String redirectUrl) {
        this.store = store;
        this.payments = payments;
        this.provider = provider;
        this.storeId = storeId;
        this.productId = productId;
        this.variantId = variantId;
        this.testMode = testMode;
        this.redirectUrl = redirectUrl;
    }

    @Override
    public void run(ApplicationArguments args) {
        syncCatalog();
    }

    @Scheduled(initialDelayString = "${payments.rewire.sync-ms:900000}",
            fixedDelayString = "${payments.rewire.sync-ms:900000}")
    public synchronized void syncCatalog() {
        Instant now = Instant.now();
        PaymentApplication app = store.application("rewire").orElseGet(() -> PaymentApplication.builder()
                .appId("rewire").displayName("Rewire").active(true).acceptDamkemonJwt(false)
                .publicCheckout(true).publicLicense(true).createdAt(now).build());
        app.setUpdatedAt(now);
        store.save(app);
        payments.upsertProduct("rewire", "lifetime", "rewire_pro", storeId, productId, variantId,
                false, null, 0, true, testMode, true, redirectUrl);

        try {
            synchronizeRecurringVariants();
        } catch (RuntimeException error) {
            log.warn("Could not synchronize Rewire subscription variants from Lemon Squeezy: {}", error.getMessage());
        }
    }

    private void synchronizeRecurringVariants() {
        Map<String, CatalogPlan> plansByCode = new LinkedHashMap<>();
        Set<String> ambiguousCodes = new LinkedHashSet<>();
        for (PaymentProviderAdminService.ProviderVariant variant
                : provider.catalog("rewire", storeId, testMode).variants()) {
            CatalogPlan plan = recurringPlan(variant);
            if (plan == null || !"published".equalsIgnoreCase(variant.status())
                    || !Long.toString(productId).equals(variant.productId())) continue;
            if (plansByCode.putIfAbsent(plan.code(), plan) != null) ambiguousCodes.add(plan.code());
        }

        for (Map.Entry<String, CatalogPlan> entry : plansByCode.entrySet()) {
            String code = entry.getKey();
            if (ambiguousCodes.contains(code)) {
                log.warn("Multiple Rewire {} variants exist in Lemon Squeezy; keeping the current mapping", code);
                continue;
            }
            CatalogPlan plan = entry.getValue();
            PaymentProviderAdminService.ProviderVariant variant = plan.variant();
            long providerVariantId = providerId(variant.id());
            PaymentProduct existing = store.product("rewire", code).orElse(null);
            if (existing != null && existing.getVariantId() != providerVariantId) {
                log.warn("Rewire {} is already mapped to variant {}; refusing to replace it with {}",
                        code, existing.getVariantId(), providerVariantId);
                continue;
            }
            payments.upsertProduct("rewire", code, plan.entitlementCode(), storeId, productId,
                    providerVariantId, true, variant.billingInterval(), variant.billingIntervalCount(),
                    variant.licenseKeys(), testMode, existing == null || existing.isActive(), redirectUrl);
        }
    }

    private static CatalogPlan recurringPlan(PaymentProviderAdminService.ProviderVariant variant) {
        if (!variant.subscription() || variant.billingIntervalCount() == null
                || variant.billingIntervalCount() != 1 || variant.billingInterval() == null) return null;
        String cadence = switch (variant.billingInterval().toLowerCase(Locale.ROOT)) {
            case "week" -> "weekly";
            case "month" -> "monthly";
            case "year" -> "yearly";
            default -> null;
        };
        if (cadence == null) return null;
        String tier = variant.name() == null ? "" : variant.name().toLowerCase(Locale.ROOT)
                .replaceAll("\\b(daily|weekly|monthly|yearly|day|week|month|year|subscription|plan)\\b", " ")
                .replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        if (!tier.matches("[a-z0-9][a-z0-9_-]{0,30}")) return null;
        return new CatalogPlan(tier + "_" + cadence, "rewire_" + tier, variant);
    }

    private record CatalogPlan(String code, String entitlementCode,
                               PaymentProviderAdminService.ProviderVariant variant) {}

    private static long providerId(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) {
            throw new IllegalArgumentException("Lemon Squeezy returned an invalid variant ID");
        }
        return Long.parseLong(value);
    }
}
