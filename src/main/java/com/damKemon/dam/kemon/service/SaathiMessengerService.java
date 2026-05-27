package com.damKemon.dam.kemon.service;

import com.damKemon.dam.kemon.model.Product;
import com.damKemon.dam.kemon.model.SaathiAccount;
import com.damKemon.dam.kemon.model.SaathiProduct;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The "actually send a Messenger reply" half of the bot. Webhook receipt
 * lives in {@link com.damKemon.dam.kemon.controller.SaathiMessengerController};
 * this service owns the Graph API call that turns a computed reply into
 * an actual message on the customer's Messenger thread.
 *
 * <p>Three responsibilities:
 * <ol>
 *   <li>{@link #buildReply} — format a {@link com.damKemon.dam.kemon.service.SaathiService.LiveAssistResult}
 *       into a Bangla/English mixed message that mirrors what BD F-commerce
 *       sellers actually type. Same shape as the "Copy to chat" preview in
 *       the dashboard so the bot reads consistent across channels.</li>
 *   <li>{@link #sendReply} — POST to {@code graph.facebook.com/v18.0/me/messages}
 *       with the Saathi's Page Access Token. Returns {@code true} on 200
 *       so callers can log success / surface failures.</li>
 *   <li>{@link #connect} — store a (pageId, pageAccessToken) pair on the
 *       Saathi. Token validation is best-effort: we ping {@code /me} with
 *       the token and reject if it 401s.</li>
 * </ol>
 *
 * <p>Why a separate service: keeps the webhook controller thin and lets
 * the dashboard "Test bot" endpoint reuse {@link #buildReply} without
 * sending anything live.
 */
@Service
public class SaathiMessengerService {

    private static final Logger log = LoggerFactory.getLogger(SaathiMessengerService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String GRAPH = "https://graph.facebook.com/v18.0";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .build();

    @Value("${saathi.fb.graph-base:" + GRAPH + "}")
    private String graphBase;

    /**
     * Best-effort token validation. Returns the Page name on success or
     * null on auth failure. Used by the connect endpoint to verify the
     * operator pasted a working token before we persist it.
     */
    public String validateAndGetPageName(String pageId, String pageAccessToken) {
        if (pageId == null || pageAccessToken == null) return null;
        try {
            URI uri = URI.create(graphBase + "/" + pageId
                    + "?fields=name&access_token=" + java.net.URLEncoder.encode(pageAccessToken, java.nio.charset.StandardCharsets.UTF_8));
            HttpRequest req = HttpRequest.newBuilder(uri).GET().timeout(Duration.ofSeconds(8)).build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.debug("Saathi messenger: token validation got HTTP {} → {}", resp.statusCode(), resp.body());
                return null;
            }
            return MAPPER.readTree(resp.body()).path("name").asText(null);
        } catch (Exception e) {
            log.debug("Saathi messenger: token validation failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Send a reply to a Messenger recipient. Returns {@code true} on a 200
     * from Graph; logs and returns {@code false} otherwise (no exception
     * leaks to the webhook handler — a failed send shouldn't break the
     * 200 we owe Meta).
     */
    public boolean sendReply(SaathiAccount acc, String recipientId, String text) {
        if (acc == null || acc.getPageAccessToken() == null || recipientId == null || text == null) return false;
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("recipient", Map.of("id", recipientId));
            body.put("message", Map.of("text", text));
            body.put("messaging_type", "RESPONSE");

            URI uri = URI.create(graphBase + "/me/messages?access_token="
                    + java.net.URLEncoder.encode(acc.getPageAccessToken(), java.nio.charset.StandardCharsets.UTF_8));
            HttpRequest req = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 == 2) return true;
            log.warn("Saathi messenger send to {} failed: HTTP {} → {}", recipientId, resp.statusCode(), resp.body());
            return false;
        } catch (Exception e) {
            log.warn("Saathi messenger send threw: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Format a LiveAssist result into a customer-facing Messenger reply.
     * Mirrors the in-app preview so a seller who tests via the dashboard
     * sees the exact same text the bot will send. Bangla/English mixed —
     * matches how BD F-commerce DMs actually read.
     */
    public String buildReply(SaathiService.LiveAssistResult result) {
        if (result == null || result.getMatch() == null) {
            return "ধন্যবাদ! আপনার question টা একটু check করে confirm করছি — হয়তো আমাদের stock এ নেই, তবে close alternative দিতে পারব।";
        }
        Product p = result.getMatch();
        String name = p.getName() == null ? "this item" : p.getName();
        Double yours = result.getYourListing() == null ? null : result.getYourListing().getListedPrice();
        Double market = p.getLowestPrice();

        if (yours != null && market != null) {
            double diff = yours - market;
            if (Math.abs(diff) <= 100) {
                return name + " — আমাদের price ৳" + fmt(yours)
                        + ". Market rate এ আছি, COD available. কোন variant চান বললে confirm করি।";
            }
            if (diff < 0) {
                return name + " — আমাদের price ৳" + fmt(yours)
                        + ". Market এর চেয়ে ৳" + fmt(-diff) + " কম। In stock, COD available।";
            }
            return name + " — আমাদের price ৳" + fmt(yours)
                    + ". Bulk এ negotiable, today এ confirm করলে discount দিতে পারি।";
        }
        if (yours != null) {
            return name + " — আমাদের price ৳" + fmt(yours) + ". In stock, COD available।";
        }
        if (market != null) {
            return name + " — Current market lowest ৳" + fmt(market)
                    + ". আমাদের stock check করে আপনাকে confirm করছি।";
        }
        return name + " — Stock check করে দাম জানাচ্ছি, একটু সময় দিন।";
    }

    private static String fmt(double n) {
        return String.format(Locale.US, "%,.0f", n);
    }
}
