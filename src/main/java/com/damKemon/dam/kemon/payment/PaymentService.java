package com.damKemon.dam.kemon.payment;

import com.damKemon.dam.kemon.payment.model.PaymentApplication;
import com.damKemon.dam.kemon.payment.model.PaymentCheckout;
import com.damKemon.dam.kemon.payment.model.PaymentEntitlement;
import com.damKemon.dam.kemon.payment.model.PaymentLicense;
import com.damKemon.dam.kemon.payment.model.PaymentOrder;
import com.damKemon.dam.kemon.payment.model.PaymentProduct;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class PaymentService {
    private static final String PROVIDER = "lemon_squeezy";
    private static final Pattern APP_ID = Pattern.compile("[a-z0-9][a-z0-9-]{2,39}");
    private static final Pattern CODE = Pattern.compile("[a-z0-9][a-z0-9_-]{1,39}");
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9._:-]{8,128}");
    private static final Pattern INSTALLATION_ID = Pattern.compile("[A-Za-z0-9_-]{20,128}");
    private static final Pattern EXTERNAL_SUBJECT = Pattern.compile("[A-Za-z0-9@._:+-]{1,128}");
    private static final Pattern INSTANCE_NAME = Pattern.compile("[A-Za-z0-9 ._:@+-]{1,128}");

    private final PaymentStore store;
    private final PaymentSecurity security;
    private final LemonSqueezyClient lemon;

    public PaymentService(PaymentStore store, PaymentSecurity security, LemonSqueezyClient lemon) {
        this.store = store;
        this.security = security;
        this.lemon = lemon;
    }

    public record Caller(String jwtUserId, String jwtEmail, String apiKey,
                         String externalSubject, String installationId) {}
    public record CheckoutResult(String checkoutId, String status, String checkoutUrl, Instant expiresAt, boolean testMode) {}
    public record LicenseResult(boolean valid, String status, String entitlementCode,
                                String instanceId, Instant expiresAt, Instant validatedAt, boolean testMode) {}
    public record EntitlementResult(boolean valid, String status, String entitlementCode,
                                    Instant renewsAt, Instant expiresAt, Instant validatedAt, boolean testMode) {}
    public record FulfillmentResult(String checkoutId, String providerOrderId, String productCode,
                                    String entitlementCode, String status, Instant validatedAt, boolean testMode) {}
    public record ApplicationKey(PaymentApplication application, String apiKey) {}

    public CheckoutResult createCheckout(String appId, String productCode, String idempotencyKey,
                                         Caller caller, String requestedEmail) {
        PaymentApplication app = requireApplication(appId);
        PaymentProduct product = requireProduct(appId, productCode);
        Subject subject = authenticate(app, caller, app.isPublicCheckout());
        requirePattern(IDEMPOTENCY_KEY, idempotencyKey, "invalid_idempotency_key", "Idempotency-Key must be 8-128 safe characters");

        PaymentCheckout existing = store.checkoutByIdempotency(appId, subject.id(), idempotencyKey).orElse(null);
        Instant now = Instant.now();
        PaymentCheckout checkout;
        if (existing != null) {
            if (!"FAILED".equals(existing.getStatus())) return checkoutResult(existing);
            checkout = existing;
            checkout.setStatus("CREATING");
            checkout.setExpiresAt(now.plusSeconds(30 * 60));
            checkout.setUpdatedAt(now);
            store.save(checkout);
        } else {
            checkout = PaymentCheckout.builder()
                    .id(UUID.randomUUID().toString())
                    .appId(appId).productCode(product.getCode()).entitlementCode(product.getEntitlementCode())
                    .subjectType(subject.type()).subjectId(subject.id()).idempotencyKey(idempotencyKey)
                    .status("CREATING").testMode(product.isTestMode())
                    .expiresAt(now.plusSeconds(30 * 60)).createdAt(now).updatedAt(now).build();
            try {
                store.insert(checkout);
            } catch (DuplicateKeyException race) {
                return checkoutResult(store.checkoutByIdempotency(appId, subject.id(), idempotencyKey)
                        .orElseThrow(() -> new PaymentException(HttpStatus.CONFLICT, "checkout_conflict", "Checkout request conflicted; retry")));
            }
        }

        try {
            String trustedEmail = "damkemon_user".equals(subject.type())
                    ? normalizeEmail(null, caller.jwtEmail())
                    : subject.trustedEmail() ? normalizeEmail(requestedEmail, null) : null;
            JsonNode response = lemon.createCheckout(product, checkout.getId(), trustedEmail, checkout.getExpiresAt());
            String providerId = requiredText(response, "/data/id", "provider_response_invalid");
            String url = requiredText(response, "/data/attributes/url", "provider_response_invalid");
            requireHttps(url);
            checkout.setProviderCheckoutId(providerId);
            checkout.setCheckoutUrl(url);
            checkout.setStatus("OPEN");
            checkout.setUpdatedAt(Instant.now());
            store.save(checkout);
            return checkoutResult(checkout);
        } catch (RuntimeException e) {
            checkout.setStatus("FAILED");
            checkout.setUpdatedAt(Instant.now());
            store.save(checkout);
            throw providerFailure(e);
        }
    }

    public LicenseResult activate(String appId, String productCode, Caller caller,
                                  String licenseKey, String instanceName) {
        PaymentApplication app = requireApplication(appId);
        PaymentProduct product = requireProduct(appId, productCode);
        Subject subject = authenticate(app, caller, app.isPublicLicense());
        requireLicenseKey(licenseKey);
        requirePattern(INSTANCE_NAME, instanceName, "invalid_instance_name", "Instance name must be 1-128 safe characters");

        JsonNode preview;
        try {
            preview = lemon.validateLicense(licenseKey.trim(), null);
        } catch (RuntimeException e) {
            throw providerFailure(e);
        }
        if (!preview.path("valid").asBoolean(false)) {
            throw new PaymentException(HttpStatus.FORBIDDEN, "license_invalid", "That license is not valid");
        }
        ProviderLicense verified = verifyProviderLicense(preview, product, false);
        PaymentOrder order = requirePaidOrder(verified.orderId(), app, productCode, subject, licenseKey);

        JsonNode response;
        try {
            response = lemon.activateLicense(licenseKey.trim(), instanceName);
        } catch (RuntimeException e) {
            throw providerFailure(e);
        }
        if (!response.path("activated").asBoolean(false)) {
            throw new PaymentException(HttpStatus.FORBIDDEN, "license_activation_denied", "Lemon Squeezy did not activate this license");
        }
        ProviderLicense activated = verifyProviderLicense(response, product, true);
        if (!verified.licenseId().equals(activated.licenseId()) || !verified.orderId().equals(activated.orderId())) {
            safeDeactivate(licenseKey, activated.instanceId());
            throw new PaymentException(HttpStatus.BAD_GATEWAY, "provider_response_mismatch", "Payment provider returned inconsistent license data");
        }

        Instant now = Instant.now();
        PaymentLicense license = store.license(PROVIDER, activated.licenseId()).orElseGet(PaymentLicense::new);
        if (license.getId() == null) {
            license.setId(PROVIDER + ":" + activated.licenseId());
            license.setCreatedAt(now);
        }
        license.setProvider(PROVIDER);
        license.setProviderLicenseId(activated.licenseId());
        license.setProviderOrderId(activated.orderId());
        license.setCheckoutId(order.getCheckoutId());
        license.setAppId(appId);
        license.setProductCode(productCode);
        license.setLicenseKeyFingerprint(security.licenseFingerprint(licenseKey));
        license.setKeyShort(shortKey(licenseKey));
        license.setStatus(activated.status());
        license.setActivationLimit(activated.activationLimit());
        license.setActivationUsage(activated.activationUsage());
        license.setTestMode(activated.testMode());
        license.setExpiresAt(activated.expiresAt());
        license.setUpdatedAt(now);
        store.save(license);

        PaymentEntitlement entitlement = store.entitlement(appId, subject.id(), activated.instanceId()).orElseGet(PaymentEntitlement::new);
        if (entitlement.getId() == null) {
            entitlement.setId(UUID.randomUUID().toString());
            entitlement.setCreatedAt(now);
        }
        entitlement.setAppId(appId);
        entitlement.setProductCode(productCode);
        entitlement.setEntitlementCode(product.getEntitlementCode());
        entitlement.setSubjectType(subject.type());
        entitlement.setSubjectId(subject.id());
        entitlement.setCheckoutId(order.getCheckoutId());
        entitlement.setProviderOrderId(activated.orderId());
        entitlement.setProviderLicenseId(activated.licenseId());
        entitlement.setProviderInstanceId(activated.instanceId());
        entitlement.setStatus("ACTIVE");
        entitlement.setTestMode(activated.testMode());
        entitlement.setExpiresAt(activated.expiresAt());
        entitlement.setLastValidatedAt(now);
        entitlement.setUpdatedAt(now);
        store.save(entitlement);
        return result(entitlement, true);
    }

    public LicenseResult validate(String appId, Caller caller, String licenseKey, String instanceId) {
        PaymentApplication app = requireApplication(appId);
        Subject subject = authenticate(app, caller, app.isPublicLicense());
        requireLicenseKey(licenseKey);
        requirePattern(INSTALLATION_ID, instanceId, "invalid_instance_id", "Instance ID is invalid");
        PaymentEntitlement entitlement = store.entitlement(appId, subject.id(), instanceId)
                .orElseThrow(() -> new PaymentException(HttpStatus.NOT_FOUND, "entitlement_not_found", "No entitlement exists for this installation"));
        PaymentLicense license = store.license(PROVIDER, entitlement.getProviderLicenseId())
                .orElseThrow(() -> new PaymentException(HttpStatus.CONFLICT, "license_sync_pending", "License is not synchronized"));
        if (!security.licenseMatches(licenseKey, license.getLicenseKeyFingerprint())) {
            throw new PaymentException(HttpStatus.UNAUTHORIZED, "license_mismatch", "License proof is invalid");
        }
        PaymentProduct product = requireProduct(appId, entitlement.getProductCode());
        try {
            JsonNode response = lemon.validateLicense(licenseKey.trim(), instanceId);
            if (!response.path("valid").asBoolean(false)) {
                Instant now = Instant.now();
                entitlement.setStatus("REVOKED");
                entitlement.setLastValidatedAt(now);
                entitlement.setUpdatedAt(now);
                store.save(entitlement);
                return result(entitlement, false);
            }
            ProviderLicense current = verifyProviderLicense(response, product, false);
            boolean valid = current.valid() && "active".equalsIgnoreCase(current.status())
                    && entitlement.getProviderLicenseId().equals(current.licenseId());
            Instant now = Instant.now();
            entitlement.setStatus(valid ? "ACTIVE" : "REVOKED");
            entitlement.setExpiresAt(current.expiresAt());
            entitlement.setLastValidatedAt(now);
            entitlement.setUpdatedAt(now);
            store.save(entitlement);
            return result(entitlement, valid);
        } catch (PaymentProviderException e) {
            throw providerFailure(e);
        }
    }

    public LicenseResult deactivate(String appId, Caller caller, String licenseKey, String instanceId) {
        PaymentApplication app = requireApplication(appId);
        Subject subject = authenticate(app, caller, app.isPublicLicense());
        requireLicenseKey(licenseKey);
        requirePattern(INSTALLATION_ID, instanceId, "invalid_instance_id", "Instance ID is invalid");
        PaymentEntitlement entitlement = store.entitlement(appId, subject.id(), instanceId)
                .orElseThrow(() -> new PaymentException(HttpStatus.NOT_FOUND, "entitlement_not_found", "No entitlement exists for this installation"));
        PaymentLicense license = store.license(PROVIDER, entitlement.getProviderLicenseId())
                .orElseThrow(() -> new PaymentException(HttpStatus.CONFLICT, "license_sync_pending", "License is not synchronized"));
        if (!security.licenseMatches(licenseKey, license.getLicenseKeyFingerprint())) {
            throw new PaymentException(HttpStatus.UNAUTHORIZED, "license_mismatch", "License proof is invalid");
        }
        try {
            lemon.deactivateLicense(licenseKey.trim(), instanceId);
        } catch (RuntimeException e) {
            throw providerFailure(e);
        }
        entitlement.setStatus("DEACTIVATED");
        entitlement.setUpdatedAt(Instant.now());
        store.save(entitlement);
        return result(entitlement, false);
    }

    public EntitlementResult validateEntitlement(String appId, String productCode, Caller caller) {
        PaymentApplication app = requireApplication(appId);
        PaymentProduct product = requireProduct(appId, productCode);
        if (!product.isSubscription() || product.isLicenseRequired()) {
            throw new PaymentException(HttpStatus.BAD_REQUEST, "subscription_entitlement_unavailable",
                    "This product does not use direct subscription entitlements");
        }
        Subject subject = authenticate(app, caller, app.isPublicCheckout());
        PaymentEntitlement entitlement = store.entitlementByProduct(appId, subject.id(), productCode)
                .orElseThrow(() -> new PaymentException(HttpStatus.NOT_FOUND, "entitlement_not_found",
                        "No subscription entitlement exists for this account or installation"));
        Instant now = Instant.now();
        boolean valid = "ACTIVE".equals(entitlement.getStatus())
                && (entitlement.getExpiresAt() == null || entitlement.getExpiresAt().isAfter(now));
        if (!valid && "ACTIVE".equals(entitlement.getStatus())) {
            entitlement.setStatus("REVOKED");
            entitlement.setUpdatedAt(now);
            store.save(entitlement);
        }
        String providerSubscriptionId = entitlement.getProviderSubscriptionId();
        Instant renewsAt = providerSubscriptionId == null ? null
                : store.subscription(PROVIDER, providerSubscriptionId)
                .map(com.damKemon.dam.kemon.payment.model.PaymentSubscription::getRenewsAt).orElse(null);
        return new EntitlementResult(valid, entitlement.getStatus(), entitlement.getEntitlementCode(), renewsAt,
                entitlement.getExpiresAt(), now, entitlement.isTestMode());
    }

    public FulfillmentResult fulfillment(String appId, String checkoutId, Caller caller) {
        PaymentApplication app = requireApplication(appId);
        Subject subject = authenticate(app, caller, app.isPublicCheckout());
        requirePattern(IDEMPOTENCY_KEY, checkoutId, "invalid_checkout_id", "Checkout ID is invalid");
        PaymentCheckout checkout = store.checkout(checkoutId)
                .orElseThrow(() -> new PaymentException(HttpStatus.NOT_FOUND, "checkout_not_found", "Checkout not found"));
        if (!appId.equals(checkout.getAppId()) || !subject.id().equals(checkout.getSubjectId())) {
            throw new PaymentException(HttpStatus.FORBIDDEN, "checkout_not_owned", "Checkout does not belong to this installation");
        }
        PaymentOrder order = store.orderByCheckout(checkoutId)
                .orElseThrow(() -> new PaymentException(HttpStatus.CONFLICT, "payment_sync_pending", "Payment is still synchronizing; retry shortly"));
        if (!checkoutId.equals(order.getCheckoutId()) || !appId.equals(order.getAppId())
                || !checkout.getProductCode().equals(order.getProductCode())
                || !checkout.getSubjectId().equals(order.getSubjectId())) {
            throw new PaymentException(HttpStatus.CONFLICT, "payment_sync_mismatch", "Payment synchronization does not match this checkout");
        }
        if (!"PAID".equals(checkout.getStatus()) || !"paid".equalsIgnoreCase(order.getStatus())) {
            throw new PaymentException(HttpStatus.FORBIDDEN, "order_not_paid", "The linked order is not paid");
        }
        PaymentProduct product = requireProduct(appId, checkout.getProductCode());
        if (order.isTestMode() != product.isTestMode() || checkout.isTestMode() != product.isTestMode()) {
            throw new PaymentException(HttpStatus.FORBIDDEN, "provider_mode_mismatch", "Order test/live mode does not match this product");
        }
        return new FulfillmentResult(checkoutId, order.getProviderOrderId(), checkout.getProductCode(),
                checkout.getEntitlementCode(), "PAID", Instant.now(), checkout.isTestMode());
    }

    public ApplicationKey createApplication(String appId, String displayName, boolean acceptDamkemonJwt,
                                             boolean publicCheckout, boolean publicLicense) {
        requirePattern(APP_ID, appId, "invalid_app_id", "Application ID must be 3-40 lowercase characters");
        if (displayName == null || displayName.isBlank() || displayName.length() > 80) {
            throw new PaymentException(HttpStatus.BAD_REQUEST, "invalid_display_name", "Display name is required and limited to 80 characters");
        }
        if (store.application(appId).isPresent()) {
            throw new PaymentException(HttpStatus.CONFLICT, "application_exists", "Payment application already exists");
        }
        String key = security.newApiKey(appId);
        Instant now = Instant.now();
        PaymentApplication app = PaymentApplication.builder().appId(appId).displayName(displayName.trim())
                .active(true).acceptDamkemonJwt(acceptDamkemonJwt).publicCheckout(publicCheckout).publicLicense(publicLicense)
                .apiKeySha256(security.apiKeyDigest(key)).createdAt(now).updatedAt(now).build();
        return new ApplicationKey(store.save(app), key);
    }

    public ApplicationKey rotateApplicationKey(String appId) {
        PaymentApplication app = store.application(appId)
                .orElseThrow(() -> new PaymentException(HttpStatus.NOT_FOUND, "application_not_found", "Payment application not found"));
        String key = security.newApiKey(appId);
        app.setApiKeySha256(security.apiKeyDigest(key));
        app.setUpdatedAt(Instant.now());
        return new ApplicationKey(store.save(app), key);
    }

    public PaymentApplication updateApplication(String appId, String displayName, boolean active,
                                                boolean acceptDamkemonJwt, boolean publicCheckout,
                                                boolean publicLicense) {
        PaymentApplication app = store.application(appId)
                .orElseThrow(() -> new PaymentException(HttpStatus.NOT_FOUND, "application_not_found", "Payment application not found"));
        if (displayName == null || displayName.isBlank() || displayName.length() > 80) {
            throw new PaymentException(HttpStatus.BAD_REQUEST, "invalid_display_name", "Display name is required and limited to 80 characters");
        }
        app.setDisplayName(displayName.trim());
        app.setActive(active);
        app.setAcceptDamkemonJwt(acceptDamkemonJwt);
        app.setPublicCheckout(publicCheckout);
        app.setPublicLicense(publicLicense);
        app.setUpdatedAt(Instant.now());
        return store.save(app);
    }

    public PaymentProduct upsertProduct(String appId, String code, String entitlementCode,
                                        long storeId, long productId, long variantId,
                                        boolean subscription, String billingInterval, int billingIntervalCount,
                                        boolean licenseRequired, boolean testMode, boolean active, String redirectUrl) {
        if (store.application(appId).isEmpty()) throw new PaymentException(HttpStatus.NOT_FOUND, "application_not_found", "Payment application not found");
        requirePattern(CODE, code, "invalid_product_code", "Product code is invalid");
        requirePattern(CODE, entitlementCode, "invalid_entitlement_code", "Entitlement code is invalid");
        if (storeId <= 0 || productId <= 0 || variantId <= 0) {
            throw new PaymentException(HttpStatus.BAD_REQUEST, "invalid_provider_ids", "Store, product, and variant IDs must be positive");
        }
        if (redirectUrl != null && !redirectUrl.isBlank()) requireHttps(redirectUrl);
        String normalizedInterval = billingInterval(subscription, billingInterval, billingIntervalCount);
        Instant now = Instant.now();
        PaymentProduct product = store.product(appId, code).orElseGet(PaymentProduct::new);
        if (product.getId() == null) {
            product.setId(UUID.randomUUID().toString());
            product.setCreatedAt(now);
        }
        product.setAppId(appId); product.setCode(code); product.setEntitlementCode(entitlementCode);
        product.setProvider(PROVIDER); product.setStoreId(storeId); product.setProductId(productId);
        product.setVariantId(variantId); product.setSubscription(subscription);
        product.setBillingInterval(normalizedInterval); product.setBillingIntervalCount(subscription ? billingIntervalCount : 0);
        product.setLicenseRequired(licenseRequired); product.setTestMode(testMode); product.setActive(active);
        product.setRedirectUrl(blankToNull(redirectUrl)); product.setUpdatedAt(now);
        return store.save(product);
    }

    public List<PaymentApplication> applications() { return store.applications(); }
    public List<PaymentProduct> products(String appId) { return store.products(appId); }

    private Subject authenticate(PaymentApplication app, Caller caller, boolean publicAllowed) {
        if (app.isAcceptDamkemonJwt() && caller.jwtUserId() != null && !caller.jwtUserId().isBlank()) {
            return new Subject("damkemon_user", "user:" + caller.jwtUserId(), true);
        }
        if (security.apiKeyMatches(caller.apiKey(), app.getApiKeySha256())) {
            requirePattern(EXTERNAL_SUBJECT, caller.externalSubject(), "external_subject_required", "Trusted callers must send an external subject");
            return new Subject("external", security.subjectFingerprint("external", app.getAppId() + ":" + caller.externalSubject()), true);
        }
        if (publicAllowed) {
            requirePattern(INSTALLATION_ID, caller.installationId(), "installation_id_required", "A random installation ID is required");
            return new Subject("installation", security.subjectFingerprint("installation", app.getAppId() + ":" + caller.installationId()), false);
        }
        throw new PaymentException(HttpStatus.UNAUTHORIZED, "authentication_required", "Valid payment-service authentication is required");
    }

    private PaymentApplication requireApplication(String appId) {
        if (appId == null || !APP_ID.matcher(appId).matches()) throw new PaymentException(HttpStatus.NOT_FOUND, "application_not_found", "Payment application not found");
        PaymentApplication app = store.application(appId)
                .orElseThrow(() -> new PaymentException(HttpStatus.NOT_FOUND, "application_not_found", "Payment application not found"));
        if (!app.isActive()) throw new PaymentException(HttpStatus.FORBIDDEN, "application_disabled", "Payment application is disabled");
        return app;
    }

    private PaymentProduct requireProduct(String appId, String code) {
        PaymentProduct product = store.product(appId, code)
                .orElseThrow(() -> new PaymentException(HttpStatus.NOT_FOUND, "product_not_found", "Payment product not found"));
        if (!product.isActive()) throw new PaymentException(HttpStatus.FORBIDDEN, "product_disabled", "Payment product is disabled");
        return product;
    }

    private PaymentOrder requirePaidOrder(String orderId, PaymentApplication app, String productCode,
                                          Subject subject, String licenseKey) {
        PaymentOrder order = store.order(PROVIDER, orderId)
                .orElseThrow(() -> new PaymentException(HttpStatus.CONFLICT, "payment_sync_pending", "Payment is still synchronizing; retry shortly"));
        if (!app.getAppId().equals(order.getAppId()) || !productCode.equals(order.getProductCode())) {
            throw new PaymentException(HttpStatus.FORBIDDEN, "license_not_owned", "The license does not belong to this application");
        }
        boolean sameSubject = subject.id().equals(order.getSubjectId());
        boolean publicBearerProof = app.isPublicLicense() && "installation".equals(subject.type())
                && store.licenseByFingerprint(app.getAppId(), security.licenseFingerprint(licenseKey))
                .filter(known -> orderId.equals(known.getProviderOrderId())).isPresent();
        if (!sameSubject && !publicBearerProof) {
            throw new PaymentException(HttpStatus.FORBIDDEN, "license_not_owned", "The license does not belong to this account or installation");
        }
        if (!"paid".equalsIgnoreCase(order.getStatus())) {
            throw new PaymentException(HttpStatus.FORBIDDEN, "order_not_paid", "The linked order is not paid");
        }
        return order;
    }

    private ProviderLicense verifyProviderLicense(JsonNode root, PaymentProduct product, boolean requireInstance) {
        boolean valid = root.path("valid").asBoolean(root.path("activated").asBoolean(false));
        JsonNode key = root.path("license_key");
        JsonNode meta = root.path("meta");
        long storeId = meta.path("store_id").asLong(-1);
        long productId = meta.path("product_id").asLong(-1);
        long variantId = meta.path("variant_id").asLong(-1);
        if (storeId != product.getStoreId() || productId != product.getProductId() || variantId != product.getVariantId()) {
            throw new PaymentException(HttpStatus.FORBIDDEN, "provider_product_mismatch", "License is not valid for this product");
        }
        String licenseId = key.path("id").asText("");
        String orderId = Long.toString(meta.path("order_id").asLong(-1));
        String status = key.path("status").asText("");
        String instanceId = root.path("instance").path("id").asText("");
        if (licenseId.isBlank() || "-1".equals(orderId) || status.isBlank() || (requireInstance && instanceId.isBlank())) {
            throw new PaymentException(HttpStatus.BAD_GATEWAY, "provider_response_invalid", "Payment provider returned incomplete license data");
        }
        boolean hasTestMode = key.has("test_mode") || meta.has("test_mode") || root.has("test_mode");
        boolean testMode = hasTestMode
                ? key.path("test_mode").asBoolean(meta.path("test_mode").asBoolean(root.path("test_mode").asBoolean(false)))
                : product.isTestMode();
        if (hasTestMode && testMode != product.isTestMode()) {
            throw new PaymentException(HttpStatus.FORBIDDEN, "provider_mode_mismatch", "License test/live mode does not match this product");
        }
        return new ProviderLicense(valid, licenseId, orderId, status, instanceId,
                key.path("activation_limit").isNull() ? null : key.path("activation_limit").asInt(),
                key.path("activation_usage").asInt(0), testMode,
                parseInstant(key.path("expires_at").asText(null)));
    }

    private void safeDeactivate(String licenseKey, String instanceId) {
        if (instanceId == null || instanceId.isBlank()) return;
        try { lemon.deactivateLicense(licenseKey.trim(), instanceId); } catch (RuntimeException ignored) { }
    }

    private static PaymentException providerFailure(RuntimeException e) {
        if (e instanceof PaymentException payment) return payment;
        return new PaymentException(HttpStatus.BAD_GATEWAY, "payment_provider_unavailable", e.getMessage() == null ? "Payment provider is unavailable" : e.getMessage());
    }

    private static CheckoutResult checkoutResult(PaymentCheckout checkout) {
        return new CheckoutResult(checkout.getId(), checkout.getStatus(), checkout.getCheckoutUrl(), checkout.getExpiresAt(), checkout.isTestMode());
    }

    private static LicenseResult result(PaymentEntitlement entitlement, boolean valid) {
        return new LicenseResult(valid, entitlement.getStatus(), entitlement.getEntitlementCode(),
                entitlement.getProviderInstanceId(), entitlement.getExpiresAt(), entitlement.getLastValidatedAt(), entitlement.isTestMode());
    }

    private static String requiredText(JsonNode root, String pointer, String code) {
        String value = root == null ? "" : root.at(pointer).asText("");
        if (value.isBlank()) throw new PaymentException(HttpStatus.BAD_GATEWAY, code, "Payment provider returned incomplete checkout data");
        return value;
    }

    private static void requirePattern(Pattern pattern, String value, String code, String message) {
        if (value == null || !pattern.matcher(value).matches()) throw new PaymentException(HttpStatus.BAD_REQUEST, code, message);
    }

    private static void requireLicenseKey(String value) {
        if (value == null || value.isBlank() || value.length() > 256 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new PaymentException(HttpStatus.BAD_REQUEST, "invalid_license_key", "License key is required");
        }
    }

    private static void requireHttps(String value) {
        try {
            URI uri = URI.create(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) throw new IllegalArgumentException();
        } catch (IllegalArgumentException e) {
            throw new PaymentException(HttpStatus.BAD_REQUEST, "invalid_https_url", "URL must be absolute HTTPS");
        }
    }

    private static String billingInterval(boolean subscription, String interval, int count) {
        if (!subscription) {
            if ((interval != null && !interval.isBlank()) || count != 0) {
                throw new PaymentException(HttpStatus.BAD_REQUEST, "invalid_billing_interval",
                        "One-time products cannot define a billing interval");
            }
            return null;
        }
        String value = interval == null ? "" : interval.trim().toLowerCase(Locale.ROOT);
        int max = switch (value) {
            case "day" -> 365;
            case "week" -> 52;
            case "month" -> 12;
            case "year" -> 1;
            default -> 0;
        };
        if (count < 1 || count > max) {
            throw new PaymentException(HttpStatus.BAD_REQUEST, "invalid_billing_interval",
                    "Subscription billing must be 1-365 days, 1-52 weeks, 1-12 months, or 1 year");
        }
        return value;
    }

    private static String normalizeEmail(String requested, String jwtEmail) {
        String value = requested == null || requested.isBlank() ? jwtEmail : requested;
        if (value == null || value.length() > 254 || !value.contains("@") || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) return null;
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private static String shortKey(String key) {
        String trimmed = key.trim();
        return trimmed.length() <= 8 ? "***" : trimmed.substring(0, 4) + "…" + trimmed.substring(trimmed.length() - 4);
    }

    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) return null;
        try { return Instant.parse(value); } catch (DateTimeParseException ignored) { return null; }
    }

    private record Subject(String type, String id, boolean trustedEmail) {}
    private record ProviderLicense(boolean valid, String licenseId, String orderId, String status,
                                   String instanceId, Integer activationLimit, int activationUsage,
                                   boolean testMode, Instant expiresAt) {}
}
