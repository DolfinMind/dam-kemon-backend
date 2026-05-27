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

    private final com.damKemon.dam.kemon.service.SaathiMessengerService messenger;
    private final com.damKemon.dam.kemon.repository.SaathiAccountRepository accounts;

    public SaathiMessengerController(SaathiService saathi,
                                     com.damKemon.dam.kemon.service.SaathiMessengerService messenger,
                                     com.damKemon.dam.kemon.repository.SaathiAccountRepository accounts) {
        this.saathi = saathi;
        this.messenger = messenger;
        this.accounts = accounts;
    }

    /**
     * Connect a Facebook Page to this Saathi. Body: {@code {"pageId":"...","pageAccessToken":"..."}}.
     * Validates the token against Graph before persisting. Stored token
     * enables actual send-back in the webhook handler.
     */
    @org.springframework.web.bind.annotation.PostMapping("/connect")
    public ResponseEntity<?> connect(@org.springframework.web.bind.annotation.RequestBody Map<String, String> body,
                                     HttpServletRequest req) {
        String userId = userId(req);
        if (userId == null) return unauth();
        SaathiAccount acc = saathi.findByUser(userId).orElse(null);
        if (acc == null) return ResponseEntity.status(404).body(Map.of("error", "no_saathi_account"));

        String pageId = body == null ? null : body.get("pageId");
        String token  = body == null ? null : body.get("pageAccessToken");
        if (pageId == null || pageId.isBlank() || token == null || token.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "missing_fields",
                    "message", "Both pageId and pageAccessToken are required."));
        }
        String pageName = messenger.validateAndGetPageName(pageId.trim(), token.trim());
        if (pageName == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "token_invalid",
                    "message", "Facebook rejected this token. Generate a new Page Access Token in Meta Business Suite and try again."));
        }
        acc.setFacebookPageId(pageId.trim());
        acc.setFacebookPageName(pageName);
        acc.setPageAccessToken(token.trim());
        acc.setMessengerConnectedAt(java.time.LocalDateTime.now());
        acc.setUpdatedAt(java.time.LocalDateTime.now());
        accounts.save(acc);
        return ResponseEntity.ok(Map.of(
                "connected", true,
                "pageId", pageId.trim(),
                "pageName", pageName));
    }

    /** Disconnect — wipes the stored token. Webhook continues to receive but stops sending replies. */
    @org.springframework.web.bind.annotation.PostMapping("/disconnect")
    public ResponseEntity<?> disconnect(HttpServletRequest req) {
        String userId = userId(req);
        if (userId == null) return unauth();
        SaathiAccount acc = saathi.findByUser(userId).orElse(null);
        if (acc == null) return ResponseEntity.status(404).body(Map.of("error", "no_saathi_account"));
        acc.setPageAccessToken(null);
        acc.setMessengerConnectedAt(null);
        acc.setUpdatedAt(java.time.LocalDateTime.now());
        accounts.save(acc);
        return ResponseEntity.noContent().build();
    }

    /**
     * Test endpoint — given a customer question, return the exact reply
     * the bot would send right now. Does not POST to Facebook. Used by
     * the dashboard's "Test bot" widget so sellers can preview what
     * customers will receive without firing real messages.
     */
    @org.springframework.web.bind.annotation.GetMapping("/test")
    public ResponseEntity<?> testReply(@org.springframework.web.bind.annotation.RequestParam("q") String q,
                                       HttpServletRequest req) {
        String userId = userId(req);
        if (userId == null) return unauth();
        SaathiAccount acc = saathi.findByUser(userId).orElse(null);
        if (acc == null) return ResponseEntity.status(404).body(Map.of("error", "no_saathi_account"));
        if (q == null || q.trim().length() < 2) {
            return ResponseEntity.badRequest().body(Map.of("error", "q must be ≥ 2 chars"));
        }
        SaathiService.LiveAssistResult result = saathi.liveAssist(acc, q.trim(), "test");
        String reply = messenger.buildReply(result);
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("query", q.trim());
        out.put("reply", reply);
        out.put("matched", result.getMatch() != null);
        out.put("matchName", result.getMatch() == null ? null : result.getMatch().getName());
        out.put("yourPrice", result.getYourListing() == null ? null : result.getYourListing().getListedPrice());
        out.put("marketLowest", result.getMatch() == null ? null : result.getMatch().getLowestPrice());
        out.put("connected", acc.getPageAccessToken() != null);
        out.put("pageName", acc.getFacebookPageName());
        return ResponseEntity.ok(out);
    }

    private static String userId(HttpServletRequest req) {
        Object id = req.getAttribute("authUserId");
        return id instanceof String ? (String) id : null;
    }

    private static ResponseEntity<Map<String, Object>> unauth() {
        return ResponseEntity.status(401).body(Map.of("error", "sign in to use Saathi"));
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
                            String senderId = m.path("sender").path("id").asText(null);

                            SaathiService.LiveAssistResult result = saathi.liveAssist(acc, text, "messenger");
                            String reply = messenger.buildReply(result);

                            // Real send-back: only attempted when the Saathi has
                            // connected a Page Access Token. Otherwise the
                            // computed reply still lives in the SaathiQuery log
                            // for dashboard preview.
                            boolean sent = false;
                            if (acc.getPageAccessToken() != null && senderId != null) {
                                sent = messenger.sendReply(acc, senderId, reply);
                            }
                            log.info("saathi/messenger: slug={} sender={} q='{}' match={} sent={}",
                                    slug, senderId, text,
                                    result.getMatch() == null ? "none" : result.getMatch().getId(),
                                    sent);
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
