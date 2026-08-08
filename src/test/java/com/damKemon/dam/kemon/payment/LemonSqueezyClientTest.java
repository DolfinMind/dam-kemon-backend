package com.damKemon.dam.kemon.payment;

import com.damKemon.dam.kemon.payment.model.PaymentProduct;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LemonSqueezyClientTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void checkoutExpiryIsSerializedAtWholeSecondPrecision() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/checkouts", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"data\":{\"id\":\"checkout\",\"attributes\":{\"url\":\"https://example.test/checkout\"}}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/vnd.api+json");
            exchange.sendResponseHeaders(201, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        LemonSqueezyClient client = new LemonSqueezyClient(
                "http://127.0.0.1:" + server.getAddress().getPort(), "test-key", "");
        PaymentProduct product = PaymentProduct.builder().storeId(1).variantId(2).testMode(true).build();
        assertTrue(client.createCheckout(product, "checkout-1", null,
                Instant.parse("2026-08-08T09:00:00.123456789Z")).path("data").path("id").asText().equals("checkout"));

        assertTrue(requestBody.get().contains("\"expires_at\":\"2026-08-08T09:00:00Z\""));
        assertFalse(requestBody.get().contains(".123456789Z"));
    }
}
