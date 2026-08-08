package com.damKemon.dam.kemon.payment;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/payments/v1")
@ConditionalOnProperty(name = "payments.enabled", havingValue = "true")
public class PaymentController {
    private final PaymentService payments;
    private final PaymentWebhookService webhooks;

    public PaymentController(PaymentService payments, PaymentWebhookService webhooks) {
        this.payments = payments;
        this.webhooks = webhooks;
    }

    public record CheckoutRequest(String productCode, String installationId, String customerEmail) {}
    public record ActivateRequest(String productCode, String licenseKey, String installationId, String instanceName) {}
    public record LicenseRequest(String licenseKey, String installationId, String instanceId) {}

    @PostMapping("/apps/{appId}/checkouts")
    public ResponseEntity<PaymentService.CheckoutResult> checkout(
            @PathVariable String appId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = "X-Payment-Api-Key", required = false) String apiKey,
            @RequestHeader(value = "X-Payment-Subject", required = false) String externalSubject,
            @RequestBody CheckoutRequest body,
            HttpServletRequest request) {
        PaymentService.Caller caller = caller(request, apiKey, externalSubject, body.installationId());
        return noStore(payments.createCheckout(appId, body.productCode(), idempotencyKey, caller, body.customerEmail()));
    }

    @PostMapping("/apps/{appId}/licenses/activate")
    public ResponseEntity<PaymentService.LicenseResult> activate(
            @PathVariable String appId,
            @RequestHeader(value = "X-Payment-Api-Key", required = false) String apiKey,
            @RequestHeader(value = "X-Payment-Subject", required = false) String externalSubject,
            @RequestBody ActivateRequest body,
            HttpServletRequest request) {
        PaymentService.Caller caller = caller(request, apiKey, externalSubject, body.installationId());
        return noStore(payments.activate(appId, body.productCode(), caller, body.licenseKey(), body.instanceName()));
    }

    @PostMapping("/apps/{appId}/licenses/validate")
    public ResponseEntity<PaymentService.LicenseResult> validate(
            @PathVariable String appId,
            @RequestHeader(value = "X-Payment-Api-Key", required = false) String apiKey,
            @RequestHeader(value = "X-Payment-Subject", required = false) String externalSubject,
            @RequestBody LicenseRequest body,
            HttpServletRequest request) {
        PaymentService.Caller caller = caller(request, apiKey, externalSubject, body.installationId());
        return noStore(payments.validate(appId, caller, body.licenseKey(), body.instanceId()));
    }

    @PostMapping("/apps/{appId}/licenses/deactivate")
    public ResponseEntity<PaymentService.LicenseResult> deactivate(
            @PathVariable String appId,
            @RequestHeader(value = "X-Payment-Api-Key", required = false) String apiKey,
            @RequestHeader(value = "X-Payment-Subject", required = false) String externalSubject,
            @RequestBody LicenseRequest body,
            HttpServletRequest request) {
        PaymentService.Caller caller = caller(request, apiKey, externalSubject, body.installationId());
        return noStore(payments.deactivate(appId, caller, body.licenseKey(), body.instanceId()));
    }

    @PostMapping("/webhooks/lemon-squeezy")
    public ResponseEntity<PaymentWebhookService.WebhookResult> webhook(
            @RequestHeader("X-Signature") String signature,
            @RequestHeader("X-Event-Name") String eventName,
            @RequestBody byte[] payload) {
        return noStore(webhooks.process(payload, signature, eventName));
    }

    private static PaymentService.Caller caller(HttpServletRequest request, String apiKey,
                                                 String externalSubject, String installationId) {
        return new PaymentService.Caller(attribute(request, "authUserId"), attribute(request, "authUserEmail"),
                apiKey, externalSubject, installationId);
    }

    private static String attribute(HttpServletRequest request, String name) {
        Object value = request.getAttribute(name);
        return value == null ? null : String.valueOf(value);
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
