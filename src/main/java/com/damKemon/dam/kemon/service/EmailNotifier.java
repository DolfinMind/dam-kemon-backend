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
        String subject = "Price dropped: " + product.getName() + " — now " + price;
        String link = siteUrl + "/product/" + (product.getId() == null ? product.getSlug() : product.getId());

        StringBuilder saveLine = new StringBuilder();
        if (item.getPriceAtAdd() != null && item.getPriceAtAdd() > currentPrice) {
            saveLine.append("<p style=\"color:#4a4a4a;font-size:14px;\">When you added it: <b>৳")
                    .append(String.format(Locale.US, "%,.0f", item.getPriceAtAdd()))
                    .append("</b> — you'd save <b style=\"color:#3d7a00;\">৳")
                    .append(String.format(Locale.US, "%,.0f", item.getPriceAtAdd() - currentPrice))
                    .append("</b></p>");
        }

        String html = "<div style=\"font-family:-apple-system,Segoe UI,Roboto,sans-serif;max-width:520px;"
                + "margin:0 auto;background:#ffffff;border:1px solid #ecebe6;border-radius:16px;padding:28px;\">"
                + "<div style=\"font-size:13px;font-weight:700;letter-spacing:.08em;text-transform:uppercase;color:#3d7a00;\">Price drop</div>"
                + "<div style=\"font-size:19px;font-weight:800;color:#15131a;margin-top:6px;\">" + escape(product.getName()) + "</div>"
                + (product.getImageUrl() != null
                    ? "<img src=\"" + product.getImageUrl() + "\" alt=\"\" style=\"max-width:160px;max-height:160px;"
                      + "object-fit:contain;margin:16px 0;border-radius:12px;\">"
                    : "")
                + "<p style=\"color:#15131a;font-size:16px;\">Lowest right now: <b>" + price + "</b></p>"
                + saveLine
                + "<p style=\"margin:24px 0;\"><a href=\"" + link + "\" style=\"background:#9FE231;color:#15131a;"
                + "text-decoration:none;font-weight:700;font-size:14px;padding:12px 22px;border-radius:999px;"
                + "display:inline-block;\">Compare live prices</a></p>"
                + "<p style=\"color:#8a8a8a;font-size:12px;\">You get this because price alerts are on for this "
                + "product in your " + brand + " wishlist. Turn them off from your account page.</p>"
                + "</div>";

        boolean ok = resend.sendEmail(to, subject, html);
        log.info("Price drop alert {} — to={} product={} new={}",
                ok ? "sent" : "NOT sent (Resend unavailable/unconfigured)", to, product.getId(), price);
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
