package com.damKemon.dam.kemon.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;

@Service
public class CrawlerControlService {

    private static final Logger log = LoggerFactory.getLogger(CrawlerControlService.class);
    private static final Set<String> ACTIONS = Set.of("start", "stop", "restart");
    private static final String CRAWLER_URL = "http://188.166.224.53:8090";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

    private final URI baseUrl;
    private final String token;
    private final Duration timeout;
    private final HttpClient http;

    public record RemoteResponse(int status, String body) {}

    @Autowired
    public CrawlerControlService(@Value("${admin.api-key:}") String token) {
        this(CRAWLER_URL, token, REQUEST_TIMEOUT);
    }

    CrawlerControlService(String baseUrl, String token, Duration timeout) {
        this.baseUrl = parseBaseUrl(baseUrl);
        this.token = token == null ? "" : token;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    public RemoteResponse health() {
        return request("GET", "/health");
    }

    public RemoteResponse status() {
        return request("GET", "/status");
    }

    public RemoteResponse logs(int lines) {
        if (lines < 20 || lines > 1000) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "lines must be between 20 and 1000");
        }
        return request("GET", "/logs?lines=" + lines);
    }

    public RemoteResponse action(String action) {
        if (!ACTIONS.contains(action)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "action must be start, stop, or restart");
        }
        return request("POST", "/actions/" + action);
    }

    private RemoteResponse request(String method, String path) {
        if (baseUrl == null || token.length() < 32) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "crawler control is not configured");
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .timeout(timeout)
                    .header("Accept", "application/json")
                    .header("Authorization", "Bearer " + token)
                    .method(method, HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpResponse<String> response = http.send(
                    request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new RemoteResponse(response.statusCode(), response.body());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw unavailable(error);
        } catch (IOException | IllegalArgumentException error) {
            throw unavailable(error);
        }
    }

    private ResponseStatusException unavailable(Exception error) {
        log.warn("Crawler control request failed: {}", error.getMessage());
        return new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE, "crawler control is unavailable", error);
    }

    private static URI parseBaseUrl(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            URI uri = URI.create(raw.endsWith("/") ? raw.substring(0, raw.length() - 1) : raw);
            if (uri.getHost() == null || !("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))) {
                return null;
            }
            return uri;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
