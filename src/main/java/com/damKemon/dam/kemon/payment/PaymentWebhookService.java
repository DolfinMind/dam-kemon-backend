package com.damKemon.dam.kemon.payment;

import com.damKemon.dam.kemon.payment.model.PaymentCheckout;
import com.damKemon.dam.kemon.payment.model.PaymentEntitlement;
import com.damKemon.dam.kemon.payment.model.PaymentLicense;
import com.damKemon.dam.kemon.payment.model.PaymentOrder;
import com.damKemon.dam.kemon.payment.model.PaymentProduct;
import com.damKemon.dam.kemon.payment.model.PaymentSubscription;
import com.damKemon.dam.kemon.payment.model.PaymentWebhookEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
public class PaymentWebhookService {
    private static final String PROVIDER = "lemon_squeezy";
    private static final int MAX_PAYLOAD_BYTES = 256 * 1024;
    private static final Set<String> SUPPORTED = Set.of(
            "order_created", "order_refunded", "license_key_created", "license_key_updated",
            "subscription_created", "subscription_updated");

    private final PaymentStore store;
    private final PaymentSecurity security;
    private final ObjectMapper json = new ObjectMapper();

    public PaymentWebhookService(PaymentStore store, PaymentSecurity security) {
        this.store = store;
        this.security = security;
    }

    public record WebhookResult(String status, boolean duplicate) {}

    public WebhookResult process(byte[] payload, String signature, String headerEventName) {
        if (payload == null || payload.length == 0 || payload.length > MAX_PAYLOAD_BYTES) {
            throw new PaymentException(HttpStatus.BAD_REQUEST, "invalid_webhook_payload", "Webhook payload is empty or too large");
        }
        if (!security.validWebhook(payload, signature)) {
            throw new PaymentException(HttpStatus.UNAUTHORIZED, "invalid_webhook_signature", "Webhook signature is invalid");
        }

        JsonNode root;
        try {
            root = json.readTree(payload);
        } catch (Exception e) {
            throw new PaymentException(HttpStatus.BAD_REQUEST, "invalid_webhook_json", "Webhook payload is not valid JSON");
        }
        String eventName = text(root, "/meta/event_name");
        if (eventName.isBlank() || headerEventName == null || !eventName.equals(headerEventName)) {
            throw new PaymentException(HttpStatus.BAD_REQUEST, "webhook_event_mismatch", "Webhook event headers do not match the signed payload");
        }
        String digest = security.payloadDigest(payload);
        PaymentWebhookEvent event = PaymentWebhookEvent.builder().payloadSha256(digest).eventName(eventName)
                .resourceType(text(root, "/data/type")).resourceId(text(root, "/data/id"))
                .checkoutId(text(root, "/meta/custom_data/payment_checkout_id"))
                .status("RECEIVED").receivedAt(Instant.now()).build();
        try {
            store.insert(event);
        } catch (DuplicateKeyException duplicate) {
            PaymentWebhookEvent prior = store.webhook(digest).orElse(null);
            if (prior == null) {
                throw new PaymentException(HttpStatus.CONFLICT, "webhook_in_progress", "Webhook is already being processed");
            }
            if ("RECEIVED".equals(prior.getStatus())) {
                if (prior.getReceivedAt() == null || prior.getReceivedAt().isAfter(Instant.now().minusSeconds(60))) {
                    throw new PaymentException(HttpStatus.CONFLICT, "webhook_in_progress", "Webhook is already being processed");
                }
                event = prior;
                event.setErrorCode(null);
            } else if (!"FAILED".equals(prior.getStatus())) {
                return new WebhookResult("duplicate", true);
            } else {
                event = prior;
                event.setStatus("RECEIVED");
                event.setErrorCode(null);
            }
        }

        try {
            if (!SUPPORTED.contains(eventName)) {
                finish(event, "IGNORED", null);
                return new WebhookResult("ignored", false);
            }
            boolean managed = eventName.startsWith("order_")
                    ? processOrder(root, eventName, event)
                    : eventName.startsWith("subscription_")
                    ? processSubscription(root, event)
                    : processLicense(root, eventName, event);
            if (!managed) return new WebhookResult("ignored", false);
            finish(event, "PROCESSED", null);
            return new WebhookResult("processed", false);
        } catch (PaymentException e) {
            finish(event, "FAILED", e.code());
            throw e;
        } catch (RuntimeException e) {
            finish(event, "FAILED", "webhook_processing_failed");
            throw new PaymentException(HttpStatus.INTERNAL_SERVER_ERROR, "webhook_processing_failed", "Webhook could not be processed");
        }
    }

    private boolean processOrder(JsonNode root, String eventName, PaymentWebhookEvent event) {
        String checkoutId = event.getCheckoutId();
        PaymentCheckout checkout = checkoutId.isBlank() ? null : store.checkout(checkoutId).orElse(null);
        if (checkout == null) {
            finish(event, "IGNORED", "checkout_not_managed");
            return false;
        }
        PaymentProduct product = store.product(checkout.getAppId(), checkout.getProductCode())
                .orElseThrow(() -> new PaymentException(HttpStatus.CONFLICT, "product_sync_missing", "Mapped payment product is missing"));
        event.setAppId(checkout.getAppId());
        event.setTestMode(product.isTestMode());
        JsonNode attrs = root.path("data").path("attributes");
        JsonNode item = attrs.path("first_order_item");
        long storeId = attrs.path("store_id").asLong(-1);
        long productId = item.path("product_id").asLong(-1);
        long variantId = item.path("variant_id").asLong(-1);
        boolean testMode = attrs.path("test_mode").asBoolean(false);
        if (storeId != product.getStoreId() || productId != product.getProductId()
                || variantId != product.getVariantId() || testMode != product.isTestMode()) {
            throw new PaymentException(HttpStatus.BAD_REQUEST, "webhook_product_mismatch", "Webhook product mapping does not match the checkout");
        }

        String providerOrderId = text(root, "/data/id");
        if (providerOrderId.isBlank()) throw new PaymentException(HttpStatus.BAD_REQUEST, "webhook_order_missing", "Webhook order ID is missing");
        Instant now = Instant.now();
        PaymentOrder order = store.order(PROVIDER, providerOrderId).orElseGet(PaymentOrder::new);
        if (order.getId() == null) {
            order.setId(PROVIDER + ":" + providerOrderId);
            order.setCreatedAt(now);
        }
        String status = "order_refunded".equals(eventName) ? "refunded" : attrs.path("status").asText("paid").toLowerCase(Locale.ROOT);
        order.setProvider(PROVIDER); order.setProviderOrderId(providerOrderId); order.setCheckoutId(checkout.getId());
        order.setAppId(checkout.getAppId()); order.setProductCode(checkout.getProductCode());
        order.setSubjectType(checkout.getSubjectType()); order.setSubjectId(checkout.getSubjectId());
        order.setProviderCustomerId(attrs.path("customer_id").asLong(0));
        order.setProviderProductId(productId); order.setProviderVariantId(variantId);
        order.setStatus(status); order.setTotal(attrs.path("total").asLong(0));
        order.setRefundedAmount(attrs.path("refunded_amount").asLong("refunded".equals(status) ? order.getTotal() : 0));
        order.setCurrency(attrs.path("currency").asText("").toUpperCase(Locale.ROOT));
        order.setTestMode(testMode); order.setProviderCreatedAt(parseInstant(attrs.path("created_at").asText(null)));
        order.setProviderUpdatedAt(parseInstant(attrs.path("updated_at").asText(null))); order.setUpdatedAt(now);
        store.save(order);

        checkout.setStatus("refunded".equals(status) ? "REFUNDED" : "PAID");
        checkout.setUpdatedAt(now);
        store.save(checkout);
        if ("refunded".equals(status)) {
            for (PaymentEntitlement entitlement : store.entitlementsByCheckout(checkout.getId())) {
                entitlement.setStatus("REFUNDED");
                entitlement.setUpdatedAt(now);
                store.save(entitlement);
            }
        }
        return true;
    }

    private boolean processLicense(JsonNode root, String eventName, PaymentWebhookEvent event) {
        JsonNode attrs = root.path("data").path("attributes");
        String providerLicenseId = text(root, "/data/id");
        String providerOrderId = id(attrs, "order_id", root, "/data/relationships/order/data/id");
        PaymentOrder order = providerOrderId.isBlank() ? null : store.order(PROVIDER, providerOrderId).orElse(null);
        PaymentCheckout checkout = event.getCheckoutId().isBlank() ? null : store.checkout(event.getCheckoutId()).orElse(null);
        if (checkout == null && order != null) checkout = store.checkout(order.getCheckoutId()).orElse(null);
        if (checkout == null) {
            finish(event, "IGNORED", "checkout_not_managed");
            return false;
        }
        PaymentProduct product = store.product(checkout.getAppId(), checkout.getProductCode())
                .orElseThrow(() -> new PaymentException(HttpStatus.CONFLICT, "product_sync_missing", "Mapped payment product is missing"));
        event.setAppId(checkout.getAppId());
        event.setTestMode(product.isTestMode());
        long storeId = attrs.path("store_id").asLong(-1);
        long productId = attrs.path("product_id").asLong(-1);
        boolean testMode = product.isTestMode();
        if (storeId != product.getStoreId() || productId != product.getProductId()) {
            throw new PaymentException(HttpStatus.BAD_REQUEST, "webhook_product_mismatch", "Webhook product mapping does not match the checkout");
        }
        if (providerLicenseId.isBlank()) throw new PaymentException(HttpStatus.BAD_REQUEST, "webhook_license_missing", "Webhook license ID is missing");

        Instant now = Instant.now();
        PaymentLicense license = store.license(PROVIDER, providerLicenseId).orElseGet(PaymentLicense::new);
        if (license.getId() == null) {
            license.setId(PROVIDER + ":" + providerLicenseId);
            license.setCreatedAt(now);
        }
        license.setProvider(PROVIDER); license.setProviderLicenseId(providerLicenseId);
        license.setProviderOrderId(providerOrderId); license.setCheckoutId(checkout.getId());
        license.setAppId(checkout.getAppId()); license.setProductCode(checkout.getProductCode());
        String key = attrs.path("key").asText("");
        if (!key.isBlank()) {
            license.setLicenseKeyFingerprint(security.licenseFingerprint(key));
            license.setKeyShort(shortKey(key));
        } else if (!attrs.path("key_short").asText("").isBlank()) {
            license.setKeyShort(attrs.path("key_short").asText());
        }
        license.setStatus(attrs.path("status").asText("unknown"));
        license.setActivationLimit(attrs.path("activation_limit").isNull() ? null : attrs.path("activation_limit").asInt());
        license.setActivationUsage(attrs.path("instances_count").asInt(0));
        license.setTestMode(testMode); license.setExpiresAt(parseInstant(attrs.path("expires_at").asText(null)));
        license.setUpdatedAt(now);
        store.save(license);

        if ("license_key_updated".equals(eventName) && !"active".equalsIgnoreCase(license.getStatus())) {
            for (PaymentEntitlement entitlement : store.entitlementsByCheckout(checkout.getId())) {
                if (providerLicenseId.equals(entitlement.getProviderLicenseId())) {
                    entitlement.setStatus("REVOKED");
                    entitlement.setUpdatedAt(now);
                    store.save(entitlement);
                }
            }
        }
        return true;
    }

    private boolean processSubscription(JsonNode root, PaymentWebhookEvent event) {
        JsonNode attrs = root.path("data").path("attributes");
        String providerSubscriptionId = text(root, "/data/id");
        if (providerSubscriptionId.isBlank()) {
            throw new PaymentException(HttpStatus.BAD_REQUEST, "webhook_subscription_missing",
                    "Webhook subscription ID is missing");
        }
        PaymentSubscription subscription = store.subscription(PROVIDER, providerSubscriptionId)
                .orElseGet(PaymentSubscription::new);
        if (subscription.getCheckoutId() != null && !event.getCheckoutId().isBlank()
                && !subscription.getCheckoutId().equals(event.getCheckoutId())) {
            throw new PaymentException(HttpStatus.BAD_REQUEST, "webhook_checkout_mismatch",
                    "Webhook checkout does not match the managed subscription");
        }
        PaymentCheckout checkout = event.getCheckoutId().isBlank()
                ? null : store.checkout(event.getCheckoutId()).orElse(null);
        if (checkout == null && subscription.getCheckoutId() != null) {
            checkout = store.checkout(subscription.getCheckoutId()).orElse(null);
        }
        if (checkout == null) {
            finish(event, "IGNORED", "checkout_not_managed");
            return false;
        }
        PaymentProduct product = store.product(checkout.getAppId(), checkout.getProductCode())
                .orElseThrow(() -> new PaymentException(HttpStatus.CONFLICT, "product_sync_missing",
                        "Mapped payment product is missing"));
        event.setAppId(checkout.getAppId());
        event.setTestMode(product.isTestMode());
        long storeId = attrs.path("store_id").asLong(-1);
        long productId = attrs.path("product_id").asLong(-1);
        long variantId = attrs.path("variant_id").asLong(-1);
        boolean testMode = attrs.path("test_mode").asBoolean(false);
        if (!product.isSubscription() || storeId != product.getStoreId() || productId != product.getProductId()
                || variantId != product.getVariantId() || testMode != product.isTestMode()) {
            throw new PaymentException(HttpStatus.BAD_REQUEST, "webhook_product_mismatch",
                    "Webhook subscription mapping does not match the checkout");
        }

        Instant now = Instant.now();
        if (subscription.getId() == null) {
            subscription.setId(PROVIDER + ":" + providerSubscriptionId);
            subscription.setProvider(PROVIDER);
            subscription.setProviderSubscriptionId(providerSubscriptionId);
            subscription.setCreatedAt(now);
        }
        subscription.setProviderOrderId(id(attrs, "order_id", root, "/data/relationships/order/data/id"));
        subscription.setCheckoutId(checkout.getId());
        subscription.setAppId(checkout.getAppId());
        subscription.setProductCode(checkout.getProductCode());
        subscription.setSubjectType(checkout.getSubjectType());
        subscription.setSubjectId(checkout.getSubjectId());
        subscription.setStatus(attrs.path("status").asText("unknown").toLowerCase(Locale.ROOT));
        subscription.setCancelled(attrs.path("cancelled").asBoolean(false));
        subscription.setTestMode(testMode);
        subscription.setTrialEndsAt(parseInstant(attrs.path("trial_ends_at").asText(null)));
        subscription.setRenewsAt(parseInstant(attrs.path("renews_at").asText(null)));
        subscription.setEndsAt(parseInstant(attrs.path("ends_at").asText(null)));
        subscription.setProviderCreatedAt(parseInstant(attrs.path("created_at").asText(null)));
        subscription.setProviderUpdatedAt(parseInstant(attrs.path("updated_at").asText(null)));
        subscription.setUpdatedAt(now);
        store.save(subscription);

        boolean expired = "expired".equals(subscription.getStatus());
        if (product.isLicenseRequired()) {
            for (PaymentEntitlement entitlement : store.entitlementsByCheckout(checkout.getId())) {
                entitlement.setExpiresAt(subscription.getEndsAt());
                if (expired) entitlement.setStatus("REVOKED");
                entitlement.setUpdatedAt(now);
                store.save(entitlement);
            }
        } else {
            PaymentEntitlement entitlement = store.entitlementBySubscription(providerSubscriptionId)
                    .orElseGet(PaymentEntitlement::new);
            boolean created = entitlement.getId() == null;
            if (created) {
                entitlement.setId(UUID.randomUUID().toString());
                entitlement.setCreatedAt(now);
            }
            entitlement.setAppId(checkout.getAppId());
            entitlement.setProductCode(checkout.getProductCode());
            entitlement.setEntitlementCode(product.getEntitlementCode());
            entitlement.setSubjectType(checkout.getSubjectType());
            entitlement.setSubjectId(checkout.getSubjectId());
            entitlement.setCheckoutId(checkout.getId());
            entitlement.setProviderOrderId(subscription.getProviderOrderId());
            entitlement.setProviderSubscriptionId(providerSubscriptionId);
            entitlement.setProviderInstanceId("subscription:" + providerSubscriptionId);
            if (!"REFUNDED".equals(entitlement.getStatus())) {
                entitlement.setStatus(expired ? "REVOKED" : "ACTIVE");
            }
            entitlement.setTestMode(testMode);
            entitlement.setExpiresAt(subscription.getEndsAt());
            entitlement.setLastValidatedAt(now);
            entitlement.setUpdatedAt(now);
            store.save(entitlement);
        }
        return true;
    }

    private void finish(PaymentWebhookEvent event, String status, String error) {
        event.setStatus(status);
        event.setErrorCode(error);
        event.setProcessedAt(Instant.now());
        store.save(event);
    }

    private static String text(JsonNode root, String pointer) { return root.at(pointer).asText(""); }

    private static String id(JsonNode attrs, String attribute, JsonNode root, String pointer) {
        JsonNode value = attrs.path(attribute);
        if (value.isIntegralNumber()) return Long.toString(value.asLong());
        String direct = value.asText("");
        return direct.isBlank() ? text(root, pointer) : direct;
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) return null;
        try { return Instant.parse(value); } catch (DateTimeParseException ignored) { return null; }
    }

    private static String shortKey(String key) {
        String value = key.trim();
        return value.length() <= 8 ? "***" : value.substring(0, 4) + "…" + value.substring(value.length() - 4);
    }
}
