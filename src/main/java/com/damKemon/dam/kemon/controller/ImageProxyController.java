package com.damKemon.dam.kemon.controller;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Server-side image proxy for product images.
 *
 * <p>Why: many BD shop CDNs serve over HTTP (mixed content), aren't CORS
 * friendly, or hotlink-block third parties. Routing through our backend
 * fixes all three without needing a paid CDN.
 *
 * <p>Lightweight by design — we don't resize; we just cache-control hard
 * (CDN-friendly) and proxy bytes. A real image CDN should replace this
 * before we hit serious traffic.
 */
@RestController
@RequestMapping("/api/img")
public class ImageProxyController {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @GetMapping
    public ResponseEntity<byte[]> proxy(@RequestParam("u") String urlParam) {
        if (urlParam == null || urlParam.isBlank()) return ResponseEntity.badRequest().build();
        URI uri;
        try { uri = URI.create(urlParam); }
        catch (Exception e) { return ResponseEntity.badRequest().build(); }
        if (uri.getScheme() == null || !(uri.getScheme().equals("http") || uri.getScheme().equals("https"))) {
            return ResponseEntity.badRequest().build();
        }
        // Prevent SSRF — refuse private + loopback ranges. Best-effort.
        String host = uri.getHost();
        if (host == null) return ResponseEntity.badRequest().build();
        String hLow = host.toLowerCase();
        if (hLow.equals("localhost") || hLow.startsWith("127.") || hLow.startsWith("10.")
                || hLow.startsWith("192.168.") || hLow.startsWith("169.254.")
                || hLow.equals("::1")) {
            return ResponseEntity.badRequest().build();
        }

        try {
            HttpRequest req = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(10))
                    .header("User-Agent", "DamKemon/1.0 (image-proxy)")
                    .GET()
                    .build();
            HttpResponse<byte[]> resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() >= 400) {
                return ResponseEntity.status(resp.statusCode()).build();
            }
            String contentType = resp.headers().firstValue("Content-Type").orElse("image/jpeg");
            if (!contentType.startsWith("image/")) {
                return ResponseEntity.badRequest().build();
            }
            byte[] body = resp.body();
            if (body.length > 8 * 1024 * 1024) {
                return ResponseEntity.status(413).build();
            }
            HttpHeaders out = new HttpHeaders();
            out.setContentType(MediaType.parseMediaType(contentType));
            out.setCacheControl("public, max-age=86400, immutable");
            out.set("X-Image-Proxy", "damkemon");
            return new ResponseEntity<>(body, out, org.springframework.http.HttpStatus.OK);
        } catch (Exception e) {
            return ResponseEntity.status(502).build();
        }
    }
}
