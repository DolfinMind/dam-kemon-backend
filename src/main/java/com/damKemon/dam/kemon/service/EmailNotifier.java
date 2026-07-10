package com.damKemon.dam.kemon.service;

import com.damKemon.dam.kemon.model.Product;
import com.damKemon.dam.kemon.model.WishlistItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Locale;

/**
 * Outbound price-drop email, sent directly through {@link ResendService} —
 * the same rail the newsletter already uses. This replaces the old
 * queue-only stub that wrote rows to {@code outbound_email_queue} which
 * nothing ever drained (the reason "mail alerts" never arrived).
 *
 * <p>ponytail: direct send, no queue. The hourly scan fires a handful of
 * mails at current scale; add a queue + drainer only if alert volume ever
 * outgrows Resend's rate limits.
 */
@Service
public class EmailNotifier {

    private static final Logger log = LoggerFactory.getLogger(EmailNotifier.class);

    private final ResendService resend;

    @Value("${notifications.brand-name:Damkemon}")
    private String brand;

    @Value("${app.site-url:https://damkemon.com}")
    private String siteUrl;

    public EmailNotifier(ResendService resend) {
        this.resend = resend;
    }

    public void sendPriceDropAlert(String to, Product product, WishlistItem item, double currentPrice) {
        if (to == null || to.isBlank()) return;

        String price = "৳" + String.format(Locale.US, "%,.0f", currentPrice);
        String subject = "🚨 URGENT: The " + product.getName() + " you're tracking just dropped to " + price + "!";
        String link = siteUrl + "/product/" + (product.getId() == null ? product.getSlug() : product.getId());

        StringBuilder saveLine = new StringBuilder();
        if (item.getPriceAtAdd() != null && item.getPriceAtAdd() > currentPrice) {
            saveLine.append("<p style=\"color:#4a4a4a;font-size:14px;\">When you added it: <b style=\"text-decoration: line-through;\">৳")
                    .append(String.format(Locale.US, "%,.0f", item.getPriceAtAdd()))
                    .append("</b><br/>You save: <b style=\"color:#d93025;font-size:16px;\">৳")
                    .append(String.format(Locale.US, "%,.0f", item.getPriceAtAdd() - currentPrice))
                    .append("</b></p>");
        }

        String html = "<div style=\"font-family:-apple-system,Segoe UI,Roboto,sans-serif;max-width:520px;"
                + "margin:0 auto;background:#ffffff;border:2px solid #ff4444;border-radius:16px;padding:28px;\">"
                + "<div style=\"font-size:13px;font-weight:900;letter-spacing:.08em;text-transform:uppercase;color:#d93025;background:#ffebee;display:inline-block;padding:4px 10px;border-radius:4px;\">🔥 Deal Alert - Act Fast</div>"
                + "<div style=\"font-size:22px;font-weight:800;color:#15131a;margin-top:12px;line-height:1.2;\">The " + escape(product.getName()) + " you've been watching just had a massive price drop!</div>"
                + (product.getImageUrl() != null
                    ? "<img src=\"" + product.getImageUrl() + "\" alt=\"\" style=\"max-width:160px;max-height:160px;"
                      + "object-fit:contain;margin:20px 0;border-radius:12px;\">"
                    : "")
                + "<p style=\"color:#15131a;font-size:18px;\">Lowest right now: <b style=\"font-size:24px;color:#d93025;\">" + price + "</b></p>"
                + saveLine
                + "<p style=\"color:#4a4a4a;font-size:15px;font-weight:600;\">Warning: Deals like this usually sell out quickly. Compare live prices before stock runs out.</p>"
                + "<p style=\"margin:24px 0;\"><a href=\"" + link + "\" style=\"background:#d93025;color:#ffffff;"
                + "text-decoration:none;font-weight:800;font-size:16px;padding:14px 28px;border-radius:999px;"
                + "display:inline-block;box-shadow:0 4px 12px rgba(217, 48, 37, 0.3);\">Claim Deal Now</a></p>"
                + "<p style=\"color:#8a8a8a;font-size:12px;margin-top:40px;\">You are receiving this urgent alert because price tracking is enabled for this "
                + "product in your " + brand + " account. Turn alerts off from your account page if you're no longer interested.</p>"
                + "</div>";

        boolean ok = resend.sendEmail(to, subject, html);
        log.info("Price drop alert {} — to={} product={} new={}",
                ok ? "sent" : "NOT sent (Resend unavailable/unconfigured)", to, product.getId(), price);
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
