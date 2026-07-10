package com.damKemon.dam.kemon.service;

import com.damKemon.dam.kemon.config.AppRole;
import com.damKemon.dam.kemon.model.NewsletterSubscriber;
import com.damKemon.dam.kemon.repository.NewsletterSubscriberRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Weekly "biggest price drops" newsletter. Fires Monday 09:00 Asia/Dhaka, pulls
 * the current hot-drops set (the same real price drops the homepage rail shows),
 * renders an email-client-safe HTML digest, and sends one per subscriber through
 * {@link ResendService}.
 *
 * <p>Runs on the web node only — that's where the in-memory hot-drops state lives.
 * Disable with {@code newsletter.enabled=false}. Sends are throttled by a small
 * inter-message delay so a large subscriber list doesn't trip Resend's rate limit.
 *
 * <p>Copy never references how prices are gathered — it's framed entirely around
 * the saving for the shopper.
 */
@Service
public class NewsletterService {

    private static final Logger log = LoggerFactory.getLogger(NewsletterService.class);

    private final NewsletterSubscriberRepository subscribers;
    private final HotDropsService hotDrops;
    private final ResendService resend;
    private final AppRole appRole;

    @Value("${newsletter.enabled:true}")
    private boolean enabled;

    @Value("${newsletter.site-url:https://damkemon.com}")
    private String siteUrl;

    @Value("${newsletter.max-products:10}")
    private int maxProducts;

    /** Gentle pacing between sends (ms) so a big list stays under Resend's rate cap. */
    @Value("${newsletter.send-delay-ms:120}")
    private long sendDelayMs;

    public NewsletterService(NewsletterSubscriberRepository subscribers,
                             HotDropsService hotDrops,
                             ResendService resend,
                             AppRole appRole) {
        this.subscribers = subscribers;
        this.hotDrops = hotDrops;
        this.resend = resend;
        this.appRole = appRole;
    }

    @Scheduled(cron = "${newsletter.cron:0 0 9 * * MON}", zone = "Asia/Dhaka")
    public void sendWeekly() {
        if (!enabled) { log.info("Newsletter: disabled, skipping weekly send"); return; }
        if (!appRole.isWeb()) return;   // hot-drops state lives on the web node
        Map<String, Object> result = send();
        log.info("Newsletter weekly: {}", result.get("message"));
    }

    /** Operator-triggered send. Returns the real outcome so the admin UI can show it. */
    public Map<String, Object> sendManual() {
        return send();
    }

    /** Shared send path for both the weekly cron and the manual trigger. */
    private Map<String, Object> send() {
        if (!resend.isConfigured()) {
            return Map.of("success", false, "message", "Email isn't configured — set RESEND_API_KEY on the server.");
        }
        List<Map<String, Object>> picks = hotDrops.get(maxProducts);
        if (picks == null || picks.isEmpty()) {
            return Map.of("success", false, "message", "Nothing to feature yet — the price list is still empty.");
        }
        List<NewsletterSubscriber> recipients = subscribers.findAll();
        if (recipients.isEmpty()) return Map.of("success", false, "message", "No subscribers yet.");

        String subject = buildSubject(picks);
        int sent = 0, failed = 0;
        for (NewsletterSubscriber s : recipients) {
            String email = s.getEmail();
            if (email == null || email.isBlank()) continue;
            if (resend.sendEmail(email, subject, buildHtml(picks, email))) sent++;
            else failed++;
            if (sendDelayMs > 0) {
                try { Thread.sleep(sendDelayMs); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            }
        }
        log.info("Newsletter sent — {} ok, {} failed, {} products featured", sent, failed, picks.size());
        String msg = "Sent to " + sent + " subscriber" + (sent == 1 ? "" : "s")
                + (failed > 0 ? " (" + failed + " failed)" : "") + ".";
        return Map.of("success", sent > 0, "sent", sent, "failed", failed, "message", msg);
    }

    /** Send the current digest to one address — used to preview before going live. */
    public void sendTo(String email) {
        if (email == null || email.isBlank()) return;
        List<Map<String, Object>> picks = hotDrops.get(maxProducts);
        resend.sendEmail(email, buildSubject(picks), buildHtml(picks, email));
        log.info("Newsletter preview sent to {} ({} drops)", email, picks == null ? 0 : picks.size());
    }

    // ── rendering ───────────────────────────────────────────────────────────────

    String buildSubject(List<Map<String, Object>> picks) {
        double top = 0;
        for (Map<String, Object> p : picks) {
            double d = num(p.get("dropPct"));
            if (d > top) top = d;
        }
        if (top >= 5) return "This week's biggest price drops — up to " + Math.round(top) + "% off";
        return "This week's best prices on Damkemon";
    }

    String buildHtml(List<Map<String, Object>> picks, String recipientEmail) {
        StringBuilder cards = new StringBuilder();
        for (Map<String, Object> p : picks) cards.append(card(p));

        String unsub = siteUrl + "/api/newsletter/unsubscribe?email="
                + URLEncoder.encode(recipientEmail, StandardCharsets.UTF_8);

        return "<!doctype html><html><body style=\"margin:0;padding:0;background:#f6f5f1;\">"
            + "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"background:#f6f5f1;padding:24px 0;\"><tr><td align=\"center\">"
            + "<table role=\"presentation\" width=\"600\" cellpadding=\"0\" cellspacing=\"0\" style=\"max-width:600px;width:100%;font-family:-apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif;\">"
            // header
            + "<tr><td style=\"padding:8px 24px 20px;\">"
            + "<span style=\"font-size:22px;font-weight:800;letter-spacing:-0.5px;color:#15131a;\">Damkemon</span>"
            + "<div style=\"font-size:13px;color:#6b6b6b;margin-top:2px;\">The smartest way to shop online in Bangladesh</div>"
            + "</td></tr>"
            // hero band
            + "<tr><td style=\"padding:0 24px;\">"
            + "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"background:#15131a;border-radius:16px;\"><tr><td style=\"padding:22px 24px;\">"
            + "<div style=\"font-size:12px;font-weight:700;letter-spacing:1px;color:#c8f032;text-transform:uppercase;\">This week's price drops</div>"
            + "<div style=\"font-size:20px;font-weight:800;color:#ffffff;margin-top:6px;line-height:1.3;\">The biggest savings we found for you this week</div>"
            + "<div style=\"font-size:13px;color:#bdbdbd;margin-top:6px;\">Compared across every seller — here's where it's cheapest right now.</div>"
            + "</td></tr></table></td></tr>"
            // cards
            + "<tr><td style=\"padding:8px 24px 0;\">" + cards + "</td></tr>"
            // CTA
            + "<tr><td align=\"center\" style=\"padding:20px 24px 8px;\">"
            + "<a href=\"" + siteUrl + "\" style=\"display:inline-block;background:#c8f032;color:#15131a;font-weight:800;font-size:14px;text-decoration:none;padding:13px 28px;border-radius:999px;\">Browse all deals →</a>"
            + "</td></tr>"
            // growth loop: the reader is the cheapest acquisition channel
            + "<tr><td align=\"center\" style=\"padding:14px 24px 0;color:#6b6b6b;font-size:13px;line-height:1.6;\">"
            + "Watching a price? <a href=\"" + siteUrl + "\" style=\"color:#15131a;font-weight:700;\">Set a drop alert</a>"
            + " and we'll email you the moment it gets cheaper.<br>"
            + "Know someone who overpays for tech? Forward them this email."
            + "</td></tr>"
            // footer
            + "<tr><td style=\"padding:24px;color:#9a9a9a;font-size:11px;line-height:1.6;text-align:center;\">"
            + "You're getting this because you subscribed to the Damkemon weekly.<br>"
            + "<a href=\"" + unsub + "\" style=\"color:#6b6b6b;text-decoration:underline;\">Unsubscribe</a>"
            + " &nbsp;·&nbsp; <a href=\"" + siteUrl + "\" style=\"color:#6b6b6b;text-decoration:underline;\">damkemon.com</a>"
            + "</td></tr>"
            + "</table></td></tr></table></body></html>";
    }

    private String card(Map<String, Object> p) {
        String name = str(p.get("name"));
        String img = str(p.get("imageUrl"));
        String idOrSlug = p.get("id") != null ? str(p.get("id")) : str(p.get("slug"));
        String link = siteUrl + "/product/" + idOrSlug;
        double current = num(p.get("currentPrice"));
        double peak = num(p.get("peakPrice"));
        double dropPct = num(p.get("dropPct"));
        int sellers = (int) num(p.get("sellerCount"));

        String imgCell = img.isBlank()
            ? "<td width=\"72\" style=\"width:72px;\"></td>"
            : "<td width=\"72\" style=\"width:72px;\"><img src=\"" + img + "\" width=\"64\" height=\"64\" "
              + "style=\"width:64px;height:64px;object-fit:contain;border-radius:10px;background:#ffffff;\" alt=\"\"></td>";

        String dropBadge = dropPct >= 1
            ? "<span style=\"display:inline-block;background:#eafdca;color:#3c6b00;font-size:11px;font-weight:700;padding:2px 8px;border-radius:999px;\">↓ "
              + Math.round(dropPct) + "% off</span>"
            : "";
        String wasLine = (peak > current)
            ? "<span style=\"color:#9a9a9a;font-size:12px;text-decoration:line-through;margin-left:8px;\">৳" + money(peak) + "</span>"
            : "";
        String sellerLine = sellers > 1
            ? "<div style=\"color:#6b6b6b;font-size:11px;margin-top:4px;\">" + sellers + " sellers compared</div>"
            : "";

        return "<a href=\"" + link + "\" style=\"text-decoration:none;color:inherit;\">"
            + "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" "
            + "style=\"background:#ffffff;border:1px solid #ecebe6;border-radius:14px;margin-bottom:10px;\"><tr>"
            + "<td style=\"padding:12px;\"><table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\"><tr>"
            + imgCell
            + "<td style=\"padding-left:12px;vertical-align:top;\">"
            + "<div style=\"font-size:14px;font-weight:600;color:#15131a;line-height:1.35;\">" + esc(name) + "</div>"
            + "<div style=\"margin-top:6px;\">"
            + "<span style=\"font-size:16px;font-weight:800;color:#15131a;\">৳" + money(current) + "</span>" + wasLine
            + "</div>"
            + "<div style=\"margin-top:6px;\">" + dropBadge + "</div>"
            + sellerLine
            + "</td></tr></table></td></tr></table></a>";
    }

    private static String money(double v) { return String.format(Locale.US, "%,.0f", v); }
    private static double num(Object o) { return o instanceof Number n ? n.doubleValue() : 0; }
    private static String str(Object o) { return o == null ? "" : o.toString(); }

    /** Minimal HTML escape for product names in email body. */
    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
