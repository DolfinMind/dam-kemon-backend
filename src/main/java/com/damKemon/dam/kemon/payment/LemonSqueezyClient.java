package com.damKemon.dam.kemon.payment;

import com.damKemon.dam.kemon.payment.model.PaymentProduct;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class LemonSqueezyClient {
    private static final MediaType JSON_API = MediaType.parseMediaType("application/vnd.api+json");
    private final RestClient http;
    private final String testApiKey;
    private final String liveApiKey;
    private final ObjectMapper json = new ObjectMapper();

    public LemonSqueezyClient(@Value("${payments.lemon.base-url:https://api.lemonsqueezy.com}") String baseUrl,
                              @Value("${payments.lemon.test-api-key:${payments.lemon.api-key:}}") String testApiKey,
                              @Value("${payments.lemon.live-api-key:}") String liveApiKey) {
        this.http = RestClient.builder().baseUrl(baseUrl).build();
        this.testApiKey = clean(testApiKey);
        this.liveApiKey = clean(liveApiKey);
    }

    public boolean isConfigured(boolean testMode) {
        return !apiKey(testMode).isBlank();
    }

    public JsonNode createCheckout(PaymentProduct product, String checkoutId,
                                   String customerEmail, Instant expiresAt) {
        String key = requireApiKey(product.isTestMode());

        Map<String, Object> checkoutData = new LinkedHashMap<>();
        checkoutData.put("custom", Map.of("payment_checkout_id", checkoutId));
        if (customerEmail != null && !customerEmail.isBlank()) checkoutData.put("email", customerEmail);

        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("checkout_data", checkoutData);
        attributes.put("product_options", productOptions(product));
        // Lemon Squeezy rejects fractional-second ISO-8601 timestamps (HTTP 422).
        attributes.put("expires_at", expiresAt.truncatedTo(ChronoUnit.SECONDS).toString());
        attributes.put("test_mode", product.isTestMode());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("type", "checkouts");
        data.put("attributes", attributes);
        data.put("relationships", Map.of(
                "store", Map.of("data", Map.of("type", "stores", "id", Long.toString(product.getStoreId()))),
                "variant", Map.of("data", Map.of("type", "variants", "id", Long.toString(product.getVariantId())))));

        try {
            String response = http.post().uri("/v1/checkouts")
                    .contentType(JSON_API).accept(JSON_API)
                    .header("Authorization", "Bearer " + key)
                    .body(Map.of("data", data))
                    .retrieve().body(String.class);
            return parseJson(response, "checkout");
        } catch (RestClientResponseException e) {
            throw new PaymentProviderException("Lemon Squeezy rejected checkout creation (HTTP " + e.getStatusCode().value() + ")");
        } catch (ResourceAccessException e) {
            throw new PaymentProviderException("Lemon Squeezy is unavailable", e);
        }
    }

    public JsonNode activateLicense(String licenseKey, String instanceName) {
        return licenseCall("/v1/licenses/activate", licenseKey, null, instanceName);
    }

    public JsonNode validateLicense(String licenseKey, String instanceId) {
        return licenseCall("/v1/licenses/validate", licenseKey, instanceId, null);
    }

    public JsonNode deactivateLicense(String licenseKey, String instanceId) {
        return licenseCall("/v1/licenses/deactivate", licenseKey, instanceId, null);
    }

    public JsonNode currentUser(boolean testMode) {
        return authenticatedGet("/v1/users/me", testMode);
    }

    public JsonNode products(long storeId, boolean testMode) {
        return authenticatedGet("/v1/products?filter[store_id]=" + storeId + "&page[size]=100", testMode);
    }

    public JsonNode variants(long productId, boolean testMode) {
        return authenticatedGet("/v1/variants?filter[product_id]=" + productId + "&page[size]=100", testMode);
    }

    public JsonNode webhooks(long storeId, boolean testMode) {
        return authenticatedGet("/v1/webhooks?filter[store_id]=" + storeId + "&page[size]=100", testMode);
    }

    public JsonNode order(String providerOrderId, boolean testMode) {
        return authenticatedGet("/v1/orders/" + numericId(providerOrderId), testMode);
    }

    public JsonNode license(String providerLicenseId, boolean testMode) {
        return authenticatedGet("/v1/license-keys/" + numericId(providerLicenseId), testMode);
    }

    public JsonNode updateLicense(String providerLicenseId, Integer activationLimit,
                                  String expiresAt, boolean disabled, boolean testMode) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("activation_limit", activationLimit);
        attributes.put("expires_at", expiresAt);
        attributes.put("disabled", disabled);
        return authenticatedJson("PATCH", "/v1/license-keys/" + numericId(providerLicenseId),
                Map.of("data", Map.of("type", "license-keys", "id", numericId(providerLicenseId),
                        "attributes", attributes)), testMode);
    }

    public JsonNode refundOrder(String providerOrderId, Long amount, boolean testMode) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        if (amount != null) attributes.put("amount", amount);
        return authenticatedJson("POST", "/v1/orders/" + numericId(providerOrderId) + "/refund",
                Map.of("data", Map.of("type", "orders", "id", numericId(providerOrderId),
                        "attributes", attributes)), testMode);
    }

    public JsonNode createWebhook(long storeId, String url, List<String> events,
                                  String secret, boolean testMode) {
        Map<String, Object> data = Map.of(
                "type", "webhooks",
                "attributes", Map.of("url", url, "events", events, "secret", secret),
                "relationships", Map.of("store", Map.of("data",
                        Map.of("type", "stores", "id", Long.toString(storeId)))));
        return authenticatedJson("POST", "/v1/webhooks", Map.of("data", data), testMode);
    }

    public JsonNode updateWebhook(String webhookId, String url, List<String> events,
                                  String secret, boolean testMode) {
        Map<String, Object> data = Map.of("type", "webhooks", "id", numericId(webhookId),
                "attributes", Map.of("url", url, "events", events, "secret", secret));
        return authenticatedJson("PATCH", "/v1/webhooks/" + numericId(webhookId),
                Map.of("data", data), testMode);
    }

    private JsonNode licenseCall(String path, String licenseKey, String instanceId, String instanceName) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("license_key", licenseKey);
        if (instanceId != null) form.add("instance_id", instanceId);
        if (instanceName != null) form.add("instance_name", instanceName);
        try {
            String response = http.post().uri(path)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(form).retrieve().body(String.class);
            return parseJson(response, "license");
        } catch (RestClientResponseException e) {
            throw new PaymentProviderException("Lemon Squeezy license request failed (HTTP " + e.getStatusCode().value() + ")");
        } catch (ResourceAccessException e) {
            throw new PaymentProviderException("Lemon Squeezy is unavailable", e);
        }
    }

    private JsonNode authenticatedGet(String path, boolean testMode) {
        try {
            String response = http.get().uri(URI.create(path)).accept(JSON_API)
                    .header("Authorization", "Bearer " + requireApiKey(testMode))
                    .retrieve().body(String.class);
            return parseJson(response, "provider read");
        } catch (RestClientResponseException e) {
            throw providerError("read", e);
        } catch (ResourceAccessException e) {
            throw new PaymentProviderException("Lemon Squeezy is unavailable", e);
        }
    }

    private JsonNode authenticatedJson(String method, String path, Object body, boolean testMode) {
        try {
            RestClient.RequestBodySpec request = "PATCH".equals(method)
                    ? http.patch().uri(URI.create(path))
                    : http.post().uri(URI.create(path));
            String response = request.contentType(JSON_API).accept(JSON_API)
                    .header("Authorization", "Bearer " + requireApiKey(testMode))
                    .body(body).retrieve().body(String.class);
            return parseJson(response, "provider write");
        } catch (RestClientResponseException e) {
            throw providerError("write", e);
        } catch (ResourceAccessException e) {
            throw new PaymentProviderException("Lemon Squeezy is unavailable", e);
        }
    }

    private String requireApiKey(boolean testMode) {
        String value = apiKey(testMode);
        if (value.isBlank()) {
            throw new PaymentProviderException("Lemon Squeezy " + (testMode ? "test" : "live") + " API is not configured");
        }
        return value;
    }

    private String apiKey(boolean testMode) {
        return testMode ? testApiKey : liveApiKey;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static String numericId(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) {
            throw new PaymentException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "invalid_provider_id", "Provider resource ID is invalid");
        }
        return value;
    }

    private JsonNode parseJson(String response, String operation) {
        try {
            return json.readTree(response);
        } catch (JsonProcessingException e) {
            throw new PaymentProviderException("Lemon Squeezy returned an invalid " + operation + " response", e);
        }
    }

    private static PaymentProviderException providerError(String action, RestClientResponseException error) {
        return new PaymentProviderException("Lemon Squeezy rejected provider " + action
                + " (HTTP " + error.getStatusCode().value() + ")");
    }

    private static Map<String, Object> productOptions(PaymentProduct product) {
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("enabled_variants", List.of(product.getVariantId()));
        if (product.getRedirectUrl() != null && !product.getRedirectUrl().isBlank()) {
            options.put("redirect_url", product.getRedirectUrl());
        }
        return options;
    }
}
