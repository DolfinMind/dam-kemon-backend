package com.damKemon.dam.kemon.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Best-effort indexes for high-volume operational collections.
 *
 * <p>The custom {@link MongoConfig} bypasses Boot's auto-index flag. This list
 * stays explicit because legacy production rows cannot satisfy every annotated
 * unique index.
 */
@Component
public class OperationalIndexInitializer {

    private static final Logger log = LoggerFactory.getLogger(OperationalIndexInitializer.class);
    private final MongoTemplate mongo;

    public OperationalIndexInitializer(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void create() {
        ensure("events", new Index().on("ts", Sort.Direction.DESC)
                .expire(Duration.ofDays(30)).named("events_ts_ttl"));
        ensure("events", new Index().on("type", Sort.Direction.ASC)
                .on("ts", Sort.Direction.DESC).named("events_type_ts"));
        ensure("events", new Index().on("userId", Sort.Direction.ASC)
                .on("ts", Sort.Direction.DESC).named("events_user_ts"));
        ensure("events", new Index().on("anonId", Sort.Direction.ASC)
                .on("ts", Sort.Direction.DESC).named("events_anon_ts"));

        ensure("request_log", new Index().on("ts", Sort.Direction.DESC)
                .expire(Duration.ofDays(30)).named("request_log_ts_ttl"));
        ensure("request_log", new Index().on("userId", Sort.Direction.ASC)
                .on("ts", Sort.Direction.DESC).named("request_log_user_ts"));

        ensure("affiliate_clicks", new Index().on("ts", Sort.Direction.DESC)
                .named("affiliate_clicks_ts"));
        ensure("affiliate_clicks", new Index().on("productId", Sort.Direction.ASC)
                .on("ts", Sort.Direction.DESC).named("affiliate_clicks_product_ts"));
        ensure("affiliate_clicks", new Index().on("siteSlug", Sort.Direction.ASC)
                .on("ts", Sort.Direction.DESC).named("affiliate_clicks_shop_ts"));

        ensure("reviews", new Index().on("source", Sort.Direction.ASC).named("reviews_source"));
        ensure("reviews", new Index().on("status", Sort.Direction.ASC).named("reviews_status"));
        ensure("shops", new Index().on("status", Sort.Direction.ASC).named("shops_status"));
        ensure("sellers", new Index().on("slug", Sort.Direction.ASC).named("sellers_slug"));
        // Preflight legacy duplicates before enabling these unique indexes; never delete rows at boot.
        ensure("wishlist", new Index().on("userId", Sort.Direction.ASC).on("productId", Sort.Direction.ASC)
                .unique().named("wishlist_user_product_unique"));
        ensure("price_alert_notifications", new Index().on("deliveryKey", Sort.Direction.ASC)
                .unique().sparse().named("delivery_key_unique"));

        // Payment data is deliberately isolated from Damkemon catalog/account collections.
        ensure("payment_applications", new Index().on("apiKeySha256", Sort.Direction.ASC)
                .unique().sparse().named("payment_app_api_key_unique"));
        ensure("payment_products", new Index().on("appId", Sort.Direction.ASC).on("code", Sort.Direction.ASC)
                .unique().named("payment_product_app_code_unique"));
        ensure("payment_products", new Index().on("provider", Sort.Direction.ASC).on("storeId", Sort.Direction.ASC)
                .on("variantId", Sort.Direction.ASC).on("testMode", Sort.Direction.ASC)
                .unique().named("payment_product_provider_variant_unique"));
        ensure("payment_checkouts", new Index().on("appId", Sort.Direction.ASC).on("subjectId", Sort.Direction.ASC)
                .on("idempotencyKey", Sort.Direction.ASC).unique().named("payment_checkout_idempotency_unique"));
        ensure("payment_checkouts", new Index().on("providerCheckoutId", Sort.Direction.ASC)
                .unique().sparse().named("payment_checkout_provider_unique"));
        ensure("payment_checkouts", new Index().on("appId", Sort.Direction.ASC).on("testMode", Sort.Direction.ASC)
                .on("status", Sort.Direction.ASC).on("createdAt", Sort.Direction.DESC)
                .named("payment_checkout_admin_list"));
        ensure("payment_orders", new Index().on("provider", Sort.Direction.ASC).on("providerOrderId", Sort.Direction.ASC)
                .unique().named("payment_order_provider_unique"));
        ensure("payment_orders", new Index().on("appId", Sort.Direction.ASC).on("subjectId", Sort.Direction.ASC)
                .on("createdAt", Sort.Direction.DESC).named("payment_order_subject_created"));
        ensure("payment_orders", new Index().on("appId", Sort.Direction.ASC).on("testMode", Sort.Direction.ASC)
                .on("status", Sort.Direction.ASC).on("providerCreatedAt", Sort.Direction.DESC)
                .named("payment_order_admin_list"));
        ensure("payment_licenses", new Index().on("provider", Sort.Direction.ASC).on("providerLicenseId", Sort.Direction.ASC)
                .unique().named("payment_license_provider_unique"));
        ensure("payment_licenses", new Index().on("appId", Sort.Direction.ASC).on("licenseKeyFingerprint", Sort.Direction.ASC)
                .named("payment_license_fingerprint"));
        ensure("payment_licenses", new Index().on("appId", Sort.Direction.ASC).on("testMode", Sort.Direction.ASC)
                .on("status", Sort.Direction.ASC).on("createdAt", Sort.Direction.DESC)
                .named("payment_license_admin_list"));
        ensure("payment_entitlements", new Index().on("appId", Sort.Direction.ASC).on("subjectId", Sort.Direction.ASC)
                .on("providerInstanceId", Sort.Direction.ASC).unique().named("payment_entitlement_instance_unique"));
        ensure("payment_entitlements", new Index().on("checkoutId", Sort.Direction.ASC)
                .named("payment_entitlement_checkout"));
        ensure("payment_entitlements", new Index().on("appId", Sort.Direction.ASC).on("testMode", Sort.Direction.ASC)
                .on("status", Sort.Direction.ASC).on("createdAt", Sort.Direction.DESC)
                .named("payment_entitlement_admin_list"));
        ensure("payment_webhook_events", new Index().on("receivedAt", Sort.Direction.DESC)
                .named("payment_webhook_received"));
        ensure("payment_webhook_events", new Index().on("appId", Sort.Direction.ASC).on("testMode", Sort.Direction.ASC)
                .on("status", Sort.Direction.ASC).on("receivedAt", Sort.Direction.DESC)
                .named("payment_webhook_admin_list"));
        ensure("payment_admin_actions", new Index().on("appId", Sort.Direction.ASC).on("testMode", Sort.Direction.ASC)
                .on("status", Sort.Direction.ASC).on("createdAt", Sort.Direction.DESC)
                .named("payment_admin_action_list"));
    }

    private void ensure(String collection, Index index) {
        try {
            mongo.indexOps(collection).createIndex(index);
        } catch (Exception e) {
            log.warn("Could not ensure index on '{}': {}", collection, e.getMessage());
        }
    }
}
