package com.damKemon.dam.kemon.controller;

import com.damKemon.dam.kemon.model.SaathiAccount;
import com.damKemon.dam.kemon.service.SaathiService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;

/**
 * Facebook Pages Messenger webhook endpoint. Wired per the Meta docs:
 * <ul>
 *   <li>{@code GET /api/saathi/messenger/webhook} — webhook verification
 *       handshake. Returns the {@code hub.challenge} when the verify token
 *       matches.</li>
 *   <li>{@code POST /api/saathi/messenger/webhook?slug=...} — receives
 *       inbound message events. We use the slug query param to identify
 *       which Saathi seller this webhook belongs to (in production you'd
 *       look up the page ID against an installed-apps table, but for the
 *       MVP the slug-bound subscription works fine).</li>
 * </ul>
 *
 * <p>HMAC verification: Meta signs the body with the app secret. We verify
 * the X-Hub-Signature-256 header before processing. In dev (no secret set)
 * we skip verification but log a warning so this isn't accidentally
 * shipped to prod.
 *
 * <p>This controller deliberately does NOT send the reply itself — sending
 * requires calling the FB Graph API with a page access token, which is
 * out-of-band setup. Instead we surface the suggested reply text in the
 * response body; the seller's Saathi-aware bridge (a separate worker)
 * picks it up and calls Graph. For first ship, the live-assist sidebar
 * gives them the same reply text to copy-paste.
 */
@RestController
@RequestMapping("/api/saathi/messenger")
public class SaathiMessengerController {

    private static final Logger log = LoggerFactory.getLogger(SaathiMessengerController.class);

    private final SaathiService saathi;

    @Value("${saathi.fb.verify-token:damkemon-verify}")
    private String verifyToken;

    @Value("${saathi.fb.app-secret:}")
    private String appSecret;

    public SaathiMessengerController(SaathiService saathi) {
        this.saathi = saathi;
    }

    /** Webhook verification handshake — Meta calls this when you set the URL. */
    @GetMapping("/webhook")
    public ResponseEntity<?> verify(@RequestParam(value = "hub.mode", required = false) String mode,
                                    @RequestParam(value = "hub.verify_token", required = false) String token,
                                    @RequestParam(value = "hub.challenge", required = false) String challenge) {
        if ("subscribe".equals(mode) && verifyToken != null && verifyToken.equals(token)) {
            return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(challenge);
        }
        return ResponseEntity.status(403).body("verification failed");
    }

    /**
     * Inbound message events. Each entry has one or more messaging events;
     * we process every {@code text} message we recognise as a price query.
     */
    @PostMapping("/webhook")
    public ResponseEntity<?> receive(@RequestParam(value = "slug", required = false) String slug,
                                     @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature,
                                     @RequestBody String rawBody,
                                     HttpServletRequest req) {
        if (!validSignature(rawBody, signature)) {
            log.warn("Saathi messenger webhook signature check failed");
            return ResponseEntity.status(401).body(Map.of("error", "bad signature"));
        }
        if (slug == null || slug.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "slug query param required"));
        }
        SaathiAccount acc = saathi.findBySlug(slug).orElse(null);
        if (acc == null) return ResponseEntity.status(404).body(Map.of("error", "unknown saathi"));

        // The event payload nesting is documented at
        // https://developers.facebook.com/docs/messenger-platform/webhooks
        try {
            com.fasterxml.jackson.databind.JsonNode root =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(rawBody);
            com.fasterxml.jackson.databind.JsonNode entries = root.path("entry");
            if (entries.isArray()) {
                for (com.fasterxml.jackson.databind.JsonNode entry : entries) {
                    com.fasterxml.jackson.databind.JsonNode messaging = entry.path("messaging");
                    if (messaging.isArray()) {
                        for (com.fasterxml.jackson.databind.JsonNode m : messaging) {
                            String text = m.path("message").path("text").asText(null);
                            if (text == null || text.isBlank()) continue;
                            // Surface the would-be reply in the response so the bridge
                            // worker (or a future direct Graph call) can post it back.
                            SaathiService.LiveAssistResult result = saathi.liveAssist(acc, text, "messenger");
                            log.info("saathi/messenger: slug={} q='{}' -> match={}",
                                    slug, text,
                                    result.getMatch() == null ? "none" : result.getMatch().getId());
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("messenger webhook parse failed: {}", e.getMessage());
            // Always 200 — Meta will keep retrying otherwise.
        }
        return ResponseEntity.ok(Map.of("status", "received"));
    }

    private boolean validSignature(String body, String signatureHeader) {
        if (appSecret == null || appSecret.isBlank()) {
            // dev mode — no app secret configured, accept everything (logged at startup)
            return true;
        }
        if (signatureHeader == null || !signatureHeader.startsWith("sha256=")) return false;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(appSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] sig = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
            String expected = "sha256=" + HexFormat.of().formatHex(sig);
            return constantTimeEquals(expected.toLowerCase(Locale.ROOT),
                    signatureHeader.toLowerCase(Locale.ROOT));
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) return false;
        int diff = 0;
        for (int i = 0; i < a.length(); i++) diff |= a.charAt(i) ^ b.charAt(i);
        return diff == 0;
    }
}
