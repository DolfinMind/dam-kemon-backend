package com.damKemon.dam.kemon.service;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CrawlerControlServiceTest {

    private static final String TOKEN = "a".repeat(48);

    private HttpServer server;
    private final List<String> requests = new ArrayList<>();
    private final List<String> authorizations = new ArrayList<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::respond);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void forwardsOnlyTheSupportedOperationsWithTheServerSideToken() {
        CrawlerControlService service = configuredService();

        CrawlerControlService.RemoteResponse health = service.health();
        CrawlerControlService.RemoteResponse status = service.status();
        CrawlerControlService.RemoteResponse logs = service.logs(50);
        CrawlerControlService.RemoteResponse restart = service.action("restart");

        assertEquals(200, health.status());
        assertEquals(200, status.status());
        assertEquals(200, logs.status());
        assertEquals(202, restart.status());
        assertEquals(List.of(
                "GET /health",
                "GET /status",
                "GET /logs?lines=50",
                "POST /actions/restart"
        ), requests);
        assertEquals(List.of(
                "Bearer " + TOKEN,
                "Bearer " + TOKEN,
                "Bearer " + TOKEN,
                "Bearer " + TOKEN
        ), authorizations);
    }

    @Test
    void rejectsUnsupportedInputsBeforeCallingTheRemoteService() {
        CrawlerControlService service = configuredService();

        ResponseStatusException shortLog = assertThrows(
                ResponseStatusException.class, () -> service.logs(19));
        ResponseStatusException unknownAction = assertThrows(
                ResponseStatusException.class, () -> service.action("status"));

        assertEquals(HttpStatus.BAD_REQUEST, shortLog.getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, unknownAction.getStatusCode());
        assertEquals(List.of(), requests);
    }

    @Test
    void disabledOrMissingConfigurationReturnsServiceUnavailable() {
        CrawlerControlService service = new CrawlerControlService(
                false, "", "", Duration.ofSeconds(1));

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class, service::status);

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, error.getStatusCode());
    }

    @Test
    void malformedTokenConfigurationReturnsServiceUnavailable() {
        CrawlerControlService service = new CrawlerControlService(
                true,
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "a".repeat(32) + "\n",
                Duration.ofSeconds(1));

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class, service::status);

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, error.getStatusCode());
    }

    private CrawlerControlService configuredService() {
        return new CrawlerControlService(
                true,
                "http://127.0.0.1:" + server.getAddress().getPort(),
                TOKEN,
                Duration.ofSeconds(2));
    }

    private void respond(HttpExchange exchange) throws IOException {
        requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
        authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
        byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
        int status = exchange.getRequestURI().getPath().startsWith("/actions/") ? 202 : 200;
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
