package com.damKemon.dam.kemon.payment;

import com.damKemon.dam.kemon.payment.model.PaymentAdminAction;
import com.damKemon.dam.kemon.payment.model.PaymentEntitlement;
import com.damKemon.dam.kemon.payment.model.PaymentLicense;
import com.damKemon.dam.kemon.payment.model.PaymentOrder;
import com.damKemon.dam.kemon.payment.model.PaymentProduct;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Supplier;

@Service
public class PaymentProviderAdminService {
    private static final String PROVIDER = "lemon_squeezy";
    private static final List<String> WEBHOOK_EVENTS = List.of(
            "order_created", "order_refunded", "license_key_created", "license_key_updated");

    private final PaymentStore store;
    private final LemonSqueezyClient lemon;
    private final String webhookUrl;
    private final String webhookSecret;

    public PaymentProviderAdminService(PaymentStore store, LemonSqueezyClient lemon,
                                       @Value("${payments.lemon.webhook-url:}") String webhookUrl,
                                       @Value("${payments.lemon.webhook-secret:}") String webhookSecret) {
        this.store = store;
        this.lemon = lemon;
        this.webhookUrl = clean(webhookUrl);
        this.webhookSecret = clean(webhookSecret);
    }

    public record ProviderStatus(boolean testMode, boolean configured, boolean reachable,
                                 String accountId, String accountName, Instant checkedAt, String errorCode) {}
    public record ProviderProduct(String id, String name, String status, String priceFormatted,
                                  String buyNowUrl, boolean testMode) {}
    public record ProviderVariant(String id, String productId, String name, String status, Long price,
                                  boolean subscription, boolean licenseKeys, Integer activationLimit,
                                  boolean activationUnlimited, boolean testMode) {}
    public record CatalogView(List<ProviderProduct> products, List<ProviderVariant> variants, Instant fetchedAt) {}
    public record ProviderWebhook(String id, long storeId, String url, List<String> events,
                                  Instant lastSentAt, Instant updatedAt, boolean testMode) {}
    public record ProviderOrder(String id, long storeId, long customerId, long productId, long variantId,
                                String status, long subtotal, long discount, long tax, long total,
                                long refundedAmount, String currency, boolean testMode,
                                Instant createdAt, Instant updatedAt) {}
    public record ProviderLicense(String id, long storeId, long customerId, String orderId, long productId,
                                  String keyShort, String status, Integer activationLimit, int activationUsage,
                                  Instant expiresAt, boolean testMode, Instant createdAt, Instant updatedAt) {}

    public ProviderStatus status(boolean testMode) {
        if (!lemon.isConfigured(testMode)) {
            return new ProviderStatus(testMode, false, false, null, null, Instant.now(), "api_key_missing");
        }
        try {
            JsonNode response = lemon.currentUser(testMode);
            JsonNode data = response.path("data");
            return new ProviderStatus(testMode, true, true, text(data, "/id"),
                    text(data, "/attributes/name"), Instant.now(), null);
        } catch (RuntimeException e) {
            return new ProviderStatus(testMode, true, false, null, null, Instant.now(), "provider_unreachable");
        }
    }

    public CatalogView catalog(String appId, long storeId, boolean testMode) {
        requireMappedStore(appId, storeId, testMode);
        JsonNode response = lemon.products(storeId, testMode);
        List<ProviderProduct> products = new ArrayList<>();
        List<ProviderVariant> variants = new ArrayList<>();
        for (JsonNode data : response.path("data")) {
            JsonNode attrs = data.path("attributes");
            if (attrs.path("test_mode").asBoolean(false) != testMode) continue;
            String productId = data.path("id").asText("");
            products.add(new ProviderProduct(productId, attrs.path("name").asText(""),
                    attrs.path("status").asText(""), attrs.path("price_formatted").asText(""),
                    httpsOrNull(attrs.path("buy_now_url").asText(null)), testMode));
            JsonNode variantResponse = lemon.variants(numeric(productId), testMode);
            for (JsonNode variant : variantResponse.path("data")) {
                JsonNode value = variant.path("attributes");
                if (value.path("test_mode").asBoolean(false) != testMode) continue;
                variants.add(new ProviderVariant(variant.path("id").asText(""), productId,
                        value.path("name").asText(""), value.path("status").asText(""),
                        value.path("price").isNumber() ? value.path("price").asLong() : null,
                        value.path("is_subscription").asBoolean(false),
                        value.path("has_license_keys").asBoolean(false),
                        value.path("license_activation_limit").isNumber()
                                ? value.path("license_activation_limit").asInt() : null,
                        value.path("is_license_limit_unlimited").asBoolean(false), testMode));
            }
        }
        return new CatalogView(products, variants, Instant.now());
    }

    public List<ProviderWebhook> webhooks(String appId, long storeId, boolean testMode) {
        requireMappedStore(appId, storeId, testMode);
        return webhookViews(lemon.webhooks(storeId, testMode), testMode);
    }

    public ProviderWebhook ensureWebhook(String appId, long storeId, boolean testMode,
                                         String confirmedUrl, String actor) {
        requireMappedStore(appId, storeId, testMode);
        if (webhookUrl.isBlank() || webhookSecret.length() < 6 || webhookSecret.length() > 40) {
            throw new PaymentException(HttpStatus.CONFLICT, "webhook_not_configured",
                    "The server webhook URL and 6-40 character secret must be configured first");
        }
        if (!webhookUrl.equals(confirmedUrl)) {
            throw new PaymentException(HttpStatus.BAD_REQUEST, "webhook_confirmation_mismatch",
                    "Confirm the exact configured webhook URL");
        }
        return action(appId, "ENSURE_WEBHOOK", "webhook", Long.toString(storeId), testMode,
                actor, "url=" + webhookUrl, () -> {
                    List<ProviderWebhook> existing = webhookViews(lemon.webhooks(storeId, testMode), testMode);
                    ProviderWebhook match = existing.stream().filter(value -> webhookUrl.equals(value.url())).findFirst().orElse(null);
                    JsonNode response = match == null
                            ? lemon.createWebhook(storeId, webhookUrl, WEBHOOK_EVENTS, webhookSecret, testMode)
                            : lemon.updateWebhook(match.id(), webhookUrl, WEBHOOK_EVENTS, webhookSecret, testMode);
                    return webhookView(response.path("data"), testMode);
                });
    }

    public ProviderOrder order(String providerOrderId) {
        PaymentOrder local = requireOrder(providerOrderId);
        PaymentProduct product = requireProduct(local);
        ProviderOrder remote = orderView(lemon.order(providerOrderId, local.isTestMode()));
        if (remote.storeId() != product.getStoreId() || remote.productId() != product.getProductId()
                || remote.variantId() != product.getVariantId() || remote.testMode() != local.isTestMode()) {
            throw mismatch("order");
        }
        return remote;
    }

    public ProviderLicense license(String providerLicenseId) {
        PaymentLicense local = requireLicense(providerLicenseId);
        PaymentProduct product = requireProduct(local.getAppId(), local.getProductCode());
        ProviderLicense remote = licenseView(lemon.license(providerLicenseId, local.isTestMode()));
        if (remote.storeId() != product.getStoreId() || remote.productId() != product.getProductId()
                || remote.testMode() != local.isTestMode()) {
            throw mismatch("license");
        }
        return remote;
    }

    public ProviderLicense updateLicense(String providerLicenseId, Integer activationLimit, Instant expiresAt,
                                         boolean disabled, String confirmation, String actor) {
        PaymentLicense local = requireLicense(providerLicenseId);
        if (!providerLicenseId.equals(confirmation)) throw confirmation("license");
        if (activationLimit != null && (activationLimit < 1 || activationLimit > 10_000)) {
            throw new PaymentException(HttpStatus.BAD_REQUEST, "invalid_activation_limit",
                    "Activation limit must be 1-10000 or null for unlimited");
        }
        return action(local.getAppId(), "UPDATE_LICENSE", "license", providerLicenseId, local.isTestMode(),
                actor, "activationLimit=" + activationLimit + ",disabled=" + disabled + ",expiresAt=" + expiresAt,
                () -> {
                    ProviderLicense remote = licenseView(lemon.updateLicense(providerLicenseId, activationLimit,
                            expiresAt == null ? null : expiresAt.toString(), disabled, local.isTestMode()));
                    PaymentProduct product = requireProduct(local.getAppId(), local.getProductCode());
                    if (remote.storeId() != product.getStoreId() || remote.productId() != product.getProductId()
                            || remote.testMode() != local.isTestMode()) throw mismatch("license");
                    local.setStatus(remote.status());
                    local.setActivationLimit(remote.activationLimit());
                    local.setActivationUsage(remote.activationUsage());
                    local.setExpiresAt(remote.expiresAt());
                    local.setUpdatedAt(Instant.now());
                    store.save(local);
                    if (disabled) revokeEntitlements(local, "REVOKED");
                    return remote;
                });
    }

    public ProviderOrder refund(String providerOrderId, Long amount, String confirmation, String actor) {
        PaymentOrder local = requireOrder(providerOrderId);
        if (!providerOrderId.equals(confirmation)) throw confirmation("refund");
        if (amount != null && (amount < 1 || amount > local.getTotal())) {
            throw new PaymentException(HttpStatus.BAD_REQUEST, "invalid_refund_amount",
                    "Refund amount must be positive and cannot exceed the order total");
        }
        if ("refunded".equalsIgnoreCase(local.getStatus())) {
            throw new PaymentException(HttpStatus.CONFLICT, "order_already_refunded", "Order is already fully refunded");
        }
        return action(local.getAppId(), "REFUND_ORDER", "order", providerOrderId, local.isTestMode(), actor,
                amount == null ? "full refund" : "amount=" + amount, () -> {
                    ProviderOrder remote = orderView(lemon.refundOrder(providerOrderId, amount, local.isTestMode()));
                    PaymentProduct product = requireProduct(local);
                    if (remote.storeId() != product.getStoreId() || remote.productId() != product.getProductId()
                            || remote.variantId() != product.getVariantId() || remote.testMode() != local.isTestMode()) {
                        throw mismatch("order");
                    }
                    boolean full = remote.refundedAmount() >= remote.total();
                    local.setStatus(full ? "refunded" : "partial_refund");
                    local.setRefundedAmount(remote.refundedAmount());
                    local.setUpdatedAt(Instant.now());
                    store.save(local);
                    if (full) {
                        store.checkout(local.getCheckoutId()).ifPresent(checkout -> {
                            checkout.setStatus("REFUNDED");
                            checkout.setUpdatedAt(Instant.now());
                            store.save(checkout);
                        });
                        revokeEntitlements(local.getCheckoutId(), "REFUNDED");
                    }
                    return remote;
                });
    }

    private <T> T action(String appId, String actionName, String resourceType, String resourceId,
                         boolean testMode, String actor, String summary, Supplier<T> work) {
        PaymentAdminAction action = PaymentAdminAction.builder().id(UUID.randomUUID().toString())
                .appId(appId).action(actionName).resourceType(resourceType).resourceId(resourceId)
                .testMode(testMode).actor(clean(actor)).summary(summary).status("STARTED")
                .createdAt(Instant.now()).build();
        store.save(action);
        try {
            T value = work.get();
            action.setStatus("SUCCEEDED");
            action.setCompletedAt(Instant.now());
            store.save(action);
            return value;
        } catch (RuntimeException error) {
            action.setStatus("FAILED");
            action.setErrorCode(error instanceof PaymentException payment ? payment.code() : "provider_error");
            action.setCompletedAt(Instant.now());
            store.save(action);
            throw error;
        }
    }

    private PaymentOrder requireOrder(String id) {
        return store.order(PROVIDER, id).orElseThrow(() -> new PaymentException(HttpStatus.NOT_FOUND,
                "order_not_found", "Managed order not found"));
    }

    private PaymentLicense requireLicense(String id) {
        return store.license(PROVIDER, id).orElseThrow(() -> new PaymentException(HttpStatus.NOT_FOUND,
                "license_not_found", "Managed license not found"));
    }

    private PaymentProduct requireProduct(PaymentOrder order) {
        return requireProduct(order.getAppId(), order.getProductCode());
    }

    private PaymentProduct requireProduct(String appId, String code) {
        return store.product(appId, code).orElseThrow(() -> new PaymentException(HttpStatus.CONFLICT,
                "product_mapping_missing", "Managed product mapping is missing"));
    }

    private void requireMappedStore(String appId, long storeId, boolean testMode) {
        if (storeId <= 0 || store.products(appId).stream().noneMatch(product -> product.getStoreId() == storeId
                && product.isTestMode() == testMode)) {
            throw new PaymentException(HttpStatus.NOT_FOUND, "store_mapping_not_found",
                    "No managed product maps this application, store, and mode");
        }
    }

    private void revokeEntitlements(PaymentLicense license, String status) {
        revokeEntitlements(license.getCheckoutId(), status);
    }

    private void revokeEntitlements(String checkoutId, String status) {
        for (PaymentEntitlement entitlement : store.entitlementsByCheckout(checkoutId)) {
            entitlement.setStatus(status);
            entitlement.setUpdatedAt(Instant.now());
            store.save(entitlement);
        }
    }

    private static List<ProviderWebhook> webhookViews(JsonNode root, boolean testMode) {
        List<ProviderWebhook> result = new ArrayList<>();
        for (JsonNode data : root.path("data")) {
            JsonNode attrs = data.path("attributes");
            if (attrs.path("test_mode").asBoolean(false) == testMode) result.add(webhookView(data, testMode));
        }
        return result;
    }

    private static ProviderWebhook webhookView(JsonNode data, boolean expectedTestMode) {
        JsonNode attrs = data.path("attributes");
        boolean testMode = attrs.path("test_mode").asBoolean(false);
        if (testMode != expectedTestMode) throw mismatch("webhook");
        List<String> events = new ArrayList<>();
        attrs.path("events").forEach(value -> events.add(value.asText("")));
        return new ProviderWebhook(data.path("id").asText(""), attrs.path("store_id").asLong(0),
                httpsOrNull(attrs.path("url").asText(null)), events,
                instant(attrs.path("last_sent_at").asText(null)), instant(attrs.path("updated_at").asText(null)), testMode);
    }

    private static ProviderOrder orderView(JsonNode root) {
        JsonNode data = root.path("data").isMissingNode() ? root : root.path("data");
        JsonNode attrs = data.path("attributes");
        JsonNode item = attrs.path("first_order_item");
        return new ProviderOrder(data.path("id").asText(""), attrs.path("store_id").asLong(0),
                attrs.path("customer_id").asLong(0), item.path("product_id").asLong(0),
                item.path("variant_id").asLong(0), attrs.path("status").asText("").toLowerCase(Locale.ROOT),
                attrs.path("subtotal").asLong(0), attrs.path("discount_total").asLong(0),
                attrs.path("tax").asLong(0), attrs.path("total").asLong(0),
                attrs.path("refunded_amount").asLong(0), attrs.path("currency").asText("").toUpperCase(Locale.ROOT),
                attrs.path("test_mode").asBoolean(false), instant(attrs.path("created_at").asText(null)),
                instant(attrs.path("updated_at").asText(null)));
    }

    private static ProviderLicense licenseView(JsonNode root) {
        JsonNode data = root.path("data").isMissingNode() ? root : root.path("data");
        JsonNode attrs = data.path("attributes");
        int usage = attrs.path("instances_count").asInt(attrs.path("activation_usage").asInt(0));
        return new ProviderLicense(data.path("id").asText(""), attrs.path("store_id").asLong(0),
                attrs.path("customer_id").asLong(0), Long.toString(attrs.path("order_id").asLong(0)),
                attrs.path("product_id").asLong(0), attrs.path("key_short").asText(""),
                attrs.path("status").asText(""), attrs.path("activation_limit").isNumber()
                ? attrs.path("activation_limit").asInt() : null, usage,
                instant(attrs.path("expires_at").asText(null)), attrs.path("test_mode").asBoolean(false),
                instant(attrs.path("created_at").asText(null)), instant(attrs.path("updated_at").asText(null)));
    }

    private static PaymentException mismatch(String type) {
        return new PaymentException(HttpStatus.BAD_GATEWAY, "provider_" + type + "_mismatch",
                "Provider " + type + " does not match the managed application mapping");
    }

    private static PaymentException confirmation(String action) {
        return new PaymentException(HttpStatus.BAD_REQUEST, action + "_confirmation_mismatch",
                "Type the exact provider resource ID to confirm this action");
    }

    private static String text(JsonNode root, String pointer) {
        return root.at(pointer).asText("");
    }

    private static long numeric(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) throw mismatch("resource");
        try { return Long.parseLong(value); } catch (NumberFormatException e) { throw mismatch("resource"); }
    }

    private static Instant instant(String value) {
        if (value == null || value.isBlank()) return null;
        try { return Instant.parse(value); } catch (DateTimeParseException ignored) { return null; }
    }

    private static String httpsOrNull(String value) {
        return value != null && value.startsWith("https://") ? value : null;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
