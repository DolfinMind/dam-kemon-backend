package com.damKemon.dam.kemon.payment;

import com.damKemon.dam.kemon.payment.model.PaymentAdminAction;
import com.damKemon.dam.kemon.payment.model.PaymentApplication;
import com.damKemon.dam.kemon.payment.model.PaymentCheckout;
import com.damKemon.dam.kemon.payment.model.PaymentEntitlement;
import com.damKemon.dam.kemon.payment.model.PaymentLicense;
import com.damKemon.dam.kemon.payment.model.PaymentOrder;
import com.damKemon.dam.kemon.payment.model.PaymentProduct;
import com.damKemon.dam.kemon.payment.model.PaymentWebhookEvent;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/admin/payments")
@ConditionalOnProperty(name = "payments.enabled", havingValue = "true")
public class PaymentAdminController {
    private final PaymentService payments;
    private final PaymentStore store;
    private final PaymentProviderAdminService provider;

    public PaymentAdminController(PaymentService payments, PaymentStore store,
                                  PaymentProviderAdminService provider) {
        this.payments = payments;
        this.store = store;
        this.provider = provider;
    }

    public record ApplicationRequest(String appId, String displayName, boolean acceptDamkemonJwt,
                                     boolean publicCheckout, boolean publicLicense) {}
    public record ApplicationView(String appId, String displayName, boolean active, boolean acceptDamkemonJwt,
                                  boolean publicCheckout, boolean publicLicense, boolean apiKeyConfigured,
                                  Instant createdAt, Instant updatedAt) {
        static ApplicationView of(PaymentApplication value) {
            return new ApplicationView(value.getAppId(), value.getDisplayName(), value.isActive(),
                    value.isAcceptDamkemonJwt(), value.isPublicCheckout(), value.isPublicLicense(),
                    value.getApiKeySha256() != null && !value.getApiKeySha256().isBlank(),
                    value.getCreatedAt(), value.getUpdatedAt());
        }
    }
    public record ApplicationKeyView(ApplicationView application, String apiKey) {}
    public record ApplicationPolicyRequest(String displayName, boolean active, boolean acceptDamkemonJwt,
                                           boolean publicCheckout, boolean publicLicense) {}
    public record ProductRequest(String code, String entitlementCode, long storeId, long productId, long variantId,
                                 boolean testMode, boolean active, String redirectUrl) {}
    public record ProductView(String id, String appId, String code, String entitlementCode, String provider,
                              long storeId, long productId, long variantId, boolean testMode, boolean active,
                              String redirectUrl, Instant createdAt, Instant updatedAt) {
        static ProductView of(PaymentProduct value) {
            return new ProductView(value.getId(), value.getAppId(), value.getCode(), value.getEntitlementCode(),
                    value.getProvider(), value.getStoreId(), value.getProductId(), value.getVariantId(),
                    value.isTestMode(), value.isActive(), value.getRedirectUrl(), value.getCreatedAt(), value.getUpdatedAt());
        }
    }

    public record PageView<T>(List<T> items, long total, int offset, int limit) {}
    public record StatusCounts(long total, long primary, long secondary, long failed) {}
    public record Overview(long applications, long products, StatusCounts checkouts, StatusCounts orders,
                           StatusCounts licenses, StatusCounts entitlements, StatusCounts webhooks,
                           List<PaymentStore.RevenueTotal> revenue) {}
    public record CheckoutView(String id, String appId, String productCode, String entitlementCode,
                               String subjectType, String subjectRef, String status, String providerCheckoutId,
                               String checkoutUrl, boolean testMode, Instant expiresAt, Instant createdAt, Instant updatedAt) {
        static CheckoutView of(PaymentCheckout value) {
            return new CheckoutView(value.getId(), value.getAppId(), value.getProductCode(), value.getEntitlementCode(),
                    value.getSubjectType(), value.getSubjectId(), value.getStatus(), value.getProviderCheckoutId(),
                    value.getCheckoutUrl(), value.isTestMode(), value.getExpiresAt(), value.getCreatedAt(), value.getUpdatedAt());
        }
    }
    public record OrderView(String id, String providerOrderId, String checkoutId, String appId, String productCode,
                            String subjectType, String subjectRef, long providerCustomerId, long providerProductId,
                            long providerVariantId, String status, long total, long refundedAmount, String currency,
                            boolean testMode, Instant providerCreatedAt, Instant providerUpdatedAt,
                            Instant createdAt, Instant updatedAt) {
        static OrderView of(PaymentOrder value) {
            return new OrderView(value.getId(), value.getProviderOrderId(), value.getCheckoutId(), value.getAppId(),
                    value.getProductCode(), value.getSubjectType(), value.getSubjectId(), value.getProviderCustomerId(),
                    value.getProviderProductId(), value.getProviderVariantId(), value.getStatus(), value.getTotal(),
                    value.getRefundedAmount(), value.getCurrency(), value.isTestMode(), value.getProviderCreatedAt(),
                    value.getProviderUpdatedAt(), value.getCreatedAt(), value.getUpdatedAt());
        }
    }
    public record LicenseView(String id, String providerLicenseId, String providerOrderId, String checkoutId,
                              String appId, String productCode, String keyShort, String status,
                              Integer activationLimit, int activationUsage, boolean testMode, Instant expiresAt,
                              Instant createdAt, Instant updatedAt) {
        static LicenseView of(PaymentLicense value) {
            return new LicenseView(value.getId(), value.getProviderLicenseId(), value.getProviderOrderId(),
                    value.getCheckoutId(), value.getAppId(), value.getProductCode(), value.getKeyShort(), value.getStatus(),
                    value.getActivationLimit(), value.getActivationUsage(), value.isTestMode(), value.getExpiresAt(),
                    value.getCreatedAt(), value.getUpdatedAt());
        }
    }
    public record EntitlementView(String id, String appId, String productCode, String entitlementCode,
                                  String subjectType, String subjectRef, String checkoutId, String providerOrderId,
                                  String providerLicenseId, String providerInstanceId, String status, boolean testMode,
                                  Instant expiresAt, Instant lastValidatedAt, Instant createdAt, Instant updatedAt) {
        static EntitlementView of(PaymentEntitlement value) {
            return new EntitlementView(value.getId(), value.getAppId(), value.getProductCode(), value.getEntitlementCode(),
                    value.getSubjectType(), value.getSubjectId(), value.getCheckoutId(), value.getProviderOrderId(),
                    value.getProviderLicenseId(), value.getProviderInstanceId(), value.getStatus(), value.isTestMode(),
                    value.getExpiresAt(), value.getLastValidatedAt(), value.getCreatedAt(), value.getUpdatedAt());
        }
    }
    public record WebhookEventView(String payloadSha256, String eventName, String resourceType, String resourceId,
                                   String checkoutId, String appId, Boolean testMode, String status, String errorCode,
                                   Instant receivedAt, Instant processedAt) {
        static WebhookEventView of(PaymentWebhookEvent value) {
            return new WebhookEventView(value.getPayloadSha256(), value.getEventName(), value.getResourceType(),
                    value.getResourceId(), value.getCheckoutId(), value.getAppId(), value.getTestMode(),
                    value.getStatus(), value.getErrorCode(), value.getReceivedAt(), value.getProcessedAt());
        }
    }
    public record AdminActionView(String id, String appId, String action, String resourceType, String resourceId,
                                  boolean testMode, String actor, String summary, String status, String errorCode,
                                  Instant createdAt, Instant completedAt) {
        static AdminActionView of(PaymentAdminAction value) {
            return new AdminActionView(value.getId(), value.getAppId(), value.getAction(), value.getResourceType(),
                    value.getResourceId(), value.isTestMode(), value.getActor(), value.getSummary(), value.getStatus(),
                    value.getErrorCode(), value.getCreatedAt(), value.getCompletedAt());
        }
    }
    public record EnsureWebhookRequest(String appId, long storeId, boolean testMode, String confirmUrl) {}
    public record LicenseUpdateRequest(Integer activationLimit, Instant expiresAt, boolean disabled, String confirmLicenseId) {}
    public record RefundRequest(Long amount, String confirmOrderId) {}

    @PostMapping("/applications")
    public ResponseEntity<ApplicationKeyView> create(@RequestBody ApplicationRequest body) {
        PaymentService.ApplicationKey result = payments.createApplication(body.appId(), body.displayName(),
                body.acceptDamkemonJwt(), body.publicCheckout(), body.publicLicense());
        return noStore(new ApplicationKeyView(ApplicationView.of(result.application()), result.apiKey()));
    }

    @PostMapping("/applications/{appId}/rotate-key")
    public ResponseEntity<ApplicationKeyView> rotate(@PathVariable String appId) {
        PaymentService.ApplicationKey result = payments.rotateApplicationKey(appId);
        return noStore(new ApplicationKeyView(ApplicationView.of(result.application()), result.apiKey()));
    }

    @PatchMapping("/applications/{appId}")
    public ResponseEntity<ApplicationView> update(@PathVariable String appId,
                                                  @RequestBody ApplicationPolicyRequest body) {
        return noStore(ApplicationView.of(payments.updateApplication(appId, body.displayName(), body.active(),
                body.acceptDamkemonJwt(), body.publicCheckout(), body.publicLicense())));
    }

    @GetMapping("/applications")
    public ResponseEntity<List<ApplicationView>> applications() {
        return noStore(payments.applications().stream().map(ApplicationView::of).toList());
    }

    @PostMapping("/applications/{appId}/products")
    public ResponseEntity<ProductView> product(@PathVariable String appId, @RequestBody ProductRequest body) {
        return noStore(ProductView.of(payments.upsertProduct(appId, body.code(), body.entitlementCode(), body.storeId(),
                body.productId(), body.variantId(), body.testMode(), body.active(), body.redirectUrl())));
    }

    @GetMapping("/applications/{appId}/products")
    public ResponseEntity<List<ProductView>> products(@PathVariable String appId) {
        return noStore(payments.products(appId).stream().map(ProductView::of).toList());
    }

    @GetMapping("/overview")
    public ResponseEntity<Overview> overview(@RequestParam(required = false) String appId,
                                             @RequestParam(required = false) Boolean testMode) {
        long applicationCount = appId == null ? payments.applications().size() : store.application(appId).stream().count();
        long productCount = appId == null
                ? payments.applications().stream().mapToLong(app -> payments.products(app.getAppId()).size()).sum()
                : payments.products(appId).stream().filter(product -> testMode == null || product.isTestMode() == testMode).count();
        StatusCounts checkouts = new StatusCounts(
                store.count(PaymentCheckout.class, appId, testMode, null),
                store.count(PaymentCheckout.class, appId, testMode, "PAID"),
                store.count(PaymentCheckout.class, appId, testMode, "OPEN"),
                store.count(PaymentCheckout.class, appId, testMode, "FAILED"));
        StatusCounts orders = new StatusCounts(
                store.count(PaymentOrder.class, appId, testMode, null),
                store.count(PaymentOrder.class, appId, testMode, "paid"),
                store.count(PaymentOrder.class, appId, testMode, "refunded"),
                store.count(PaymentOrder.class, appId, testMode, "fraudulent"));
        StatusCounts licenses = new StatusCounts(
                store.count(PaymentLicense.class, appId, testMode, null),
                store.count(PaymentLicense.class, appId, testMode, "active"),
                store.count(PaymentLicense.class, appId, testMode, "inactive"),
                store.count(PaymentLicense.class, appId, testMode, "disabled"));
        StatusCounts entitlements = new StatusCounts(
                store.count(PaymentEntitlement.class, appId, testMode, null),
                store.count(PaymentEntitlement.class, appId, testMode, "ACTIVE"),
                store.count(PaymentEntitlement.class, appId, testMode, "DEACTIVATED"),
                store.count(PaymentEntitlement.class, appId, testMode, "REVOKED"));
        StatusCounts webhooks = new StatusCounts(
                store.countWebhooks(appId, testMode, null), store.countWebhooks(appId, testMode, "PROCESSED"),
                store.countWebhooks(appId, testMode, "IGNORED"), store.countWebhooks(appId, testMode, "FAILED"));
        return noStore(new Overview(applicationCount, productCount, checkouts, orders, licenses,
                entitlements, webhooks, store.revenue(appId, testMode)));
    }

    @GetMapping("/checkouts")
    public ResponseEntity<PageView<CheckoutView>> checkouts(@RequestParam(required = false) String appId,
                                                             @RequestParam(required = false) Boolean testMode,
                                                             @RequestParam(required = false) String status,
                                                             @RequestParam(defaultValue = "0") int offset,
                                                             @RequestParam(defaultValue = "25") int limit) {
        PaymentStore.Slice<PaymentCheckout> page = store.checkouts(appId, testMode, status, offset(offset), limit(limit));
        return noStore(page(page, page.items().stream().map(CheckoutView::of).toList()));
    }

    @GetMapping("/orders")
    public ResponseEntity<PageView<OrderView>> orders(@RequestParam(required = false) String appId,
                                                       @RequestParam(required = false) Boolean testMode,
                                                       @RequestParam(required = false) String status,
                                                       @RequestParam(defaultValue = "0") int offset,
                                                       @RequestParam(defaultValue = "25") int limit) {
        PaymentStore.Slice<PaymentOrder> page = store.orders(appId, testMode, status, offset(offset), limit(limit));
        return noStore(page(page, page.items().stream().map(OrderView::of).toList()));
    }

    @GetMapping("/licenses")
    public ResponseEntity<PageView<LicenseView>> licenses(@RequestParam(required = false) String appId,
                                                           @RequestParam(required = false) Boolean testMode,
                                                           @RequestParam(required = false) String status,
                                                           @RequestParam(defaultValue = "0") int offset,
                                                           @RequestParam(defaultValue = "25") int limit) {
        PaymentStore.Slice<PaymentLicense> page = store.licenses(appId, testMode, status, offset(offset), limit(limit));
        return noStore(page(page, page.items().stream().map(LicenseView::of).toList()));
    }

    @GetMapping("/entitlements")
    public ResponseEntity<PageView<EntitlementView>> entitlements(@RequestParam(required = false) String appId,
                                                                   @RequestParam(required = false) Boolean testMode,
                                                                   @RequestParam(required = false) String status,
                                                                   @RequestParam(defaultValue = "0") int offset,
                                                                   @RequestParam(defaultValue = "25") int limit) {
        PaymentStore.Slice<PaymentEntitlement> page = store.entitlements(appId, testMode, status, offset(offset), limit(limit));
        return noStore(page(page, page.items().stream().map(EntitlementView::of).toList()));
    }

    @GetMapping("/webhook-events")
    public ResponseEntity<PageView<WebhookEventView>> webhookEvents(@RequestParam(required = false) String appId,
                                                                     @RequestParam(required = false) Boolean testMode,
                                                                     @RequestParam(required = false) String status,
                                                                     @RequestParam(defaultValue = "0") int offset,
                                                                     @RequestParam(defaultValue = "25") int limit) {
        PaymentStore.Slice<PaymentWebhookEvent> page = store.webhooks(appId, testMode, status, offset(offset), limit(limit));
        return noStore(page(page, page.items().stream().map(WebhookEventView::of).toList()));
    }

    @GetMapping("/admin-actions")
    public ResponseEntity<PageView<AdminActionView>> adminActions(@RequestParam(required = false) String appId,
                                                                   @RequestParam(required = false) Boolean testMode,
                                                                   @RequestParam(required = false) String status,
                                                                   @RequestParam(defaultValue = "0") int offset,
                                                                   @RequestParam(defaultValue = "25") int limit) {
        PaymentStore.Slice<PaymentAdminAction> page = store.adminActions(appId, testMode, status, offset(offset), limit(limit));
        return noStore(page(page, page.items().stream().map(AdminActionView::of).toList()));
    }

    @GetMapping("/provider/status")
    public ResponseEntity<PaymentProviderAdminService.ProviderStatus> providerStatus(@RequestParam boolean testMode) {
        return noStore(provider.status(testMode));
    }

    @GetMapping("/provider/catalog")
    public ResponseEntity<PaymentProviderAdminService.CatalogView> providerCatalog(@RequestParam String appId,
                                                                                    @RequestParam long storeId,
                                                                                    @RequestParam boolean testMode) {
        return noStore(provider.catalog(appId, storeId, testMode));
    }

    @GetMapping("/provider/webhooks")
    public ResponseEntity<List<PaymentProviderAdminService.ProviderWebhook>> providerWebhooks(
            @RequestParam String appId, @RequestParam long storeId, @RequestParam boolean testMode) {
        return noStore(provider.webhooks(appId, storeId, testMode));
    }

    @PostMapping("/provider/webhooks/ensure")
    public ResponseEntity<PaymentProviderAdminService.ProviderWebhook> ensureWebhook(
            @RequestBody EnsureWebhookRequest body, HttpServletRequest request) {
        return noStore(provider.ensureWebhook(body.appId(), body.storeId(), body.testMode(), body.confirmUrl(), actor(request)));
    }

    @GetMapping("/provider/orders/{providerOrderId}")
    public ResponseEntity<PaymentProviderAdminService.ProviderOrder> providerOrder(@PathVariable String providerOrderId) {
        return noStore(provider.order(providerOrderId));
    }

    @PostMapping("/provider/orders/{providerOrderId}/refund")
    public ResponseEntity<PaymentProviderAdminService.ProviderOrder> refund(@PathVariable String providerOrderId,
                                                                            @RequestBody RefundRequest body,
                                                                            HttpServletRequest request) {
        return noStore(provider.refund(providerOrderId, body.amount(), body.confirmOrderId(), actor(request)));
    }

    @GetMapping("/provider/licenses/{providerLicenseId}")
    public ResponseEntity<PaymentProviderAdminService.ProviderLicense> providerLicense(@PathVariable String providerLicenseId) {
        return noStore(provider.license(providerLicenseId));
    }

    @PatchMapping("/provider/licenses/{providerLicenseId}")
    public ResponseEntity<PaymentProviderAdminService.ProviderLicense> updateLicense(
            @PathVariable String providerLicenseId, @RequestBody LicenseUpdateRequest body, HttpServletRequest request) {
        return noStore(provider.updateLicense(providerLicenseId, body.activationLimit(), body.expiresAt(),
                body.disabled(), body.confirmLicenseId(), actor(request)));
    }

    private static int offset(int value) {
        if (value < 0 || value > 1_000_000) throw invalidPage();
        return value;
    }

    private static int limit(int value) {
        if (value < 1 || value > 100) throw invalidPage();
        return value;
    }

    private static PaymentException invalidPage() {
        return new PaymentException(HttpStatus.BAD_REQUEST, "invalid_page", "Offset must be 0-1000000 and limit 1-100");
    }

    private static <S, T> PageView<T> page(PaymentStore.Slice<S> source, List<T> items) {
        return new PageView<>(items, source.total(), source.offset(), source.limit());
    }

    private static String actor(HttpServletRequest request) {
        Object userId = request.getAttribute("authUserId");
        return userId == null ? "admin-key" : "user:" + userId;
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
