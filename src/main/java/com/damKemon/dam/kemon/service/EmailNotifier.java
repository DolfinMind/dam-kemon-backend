package com.damKemon.dam.kemon.service;

import com.damKemon.dam.kemon.model.Product;
import com.damKemon.dam.kemon.model.WishlistItem;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Locale;

/**
 * Outbound transactional email. Today this is a queue-only stub: every
 * call appends one row to the {@code outbound_email_queue} Mongo
 * collection and logs a structured INFO line. A separate worker (lambda /
 * crob / SES/SendGrid integration) drains the queue; that piece is out of
 * scope here but the contract is already stable so it can be wired up
 * without revisiting the scheduler.
 *
 * <p>Why a queue rather than direct SMTP? Two reasons:
 * <ul>
 *   <li>Hourly batch scans can produce hundreds of alerts at once; SMTP
 *       hosts rate-limit and the scheduler should never block on that.</li>
 *   <li>Atlas free tier has no outbound SMTP. The queue lets us keep
 *       development environments faithful without configuring a mailer.</li>
 * </ul>
 */
@Service
public class EmailNotifier {

    private static final Logger log = LoggerFactory.getLogger(EmailNotifier.class);
    private static final String QUEUE = "outbound_email_queue";

    private final MongoTemplate mongo;

    @Value("${notifications.brand-name:Damkemon}")
    private String brand;

    @Value("${notifications.from-address:hello@damkemon.com}")
    private String fromAddress;

    public EmailNotifier(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    public void sendPriceDropAlert(String to, Product product, WishlistItem item, double currentPrice) {
        if (to == null || to.isBlank()) return;

        String subject = "Price dropped: " + product.getName() + " — now ৳" +
                String.format(Locale.US, "%,.0f", currentPrice);

        StringBuilder body = new StringBuilder();
        body.append("Good news — a price you're watching just dropped.\n\n");
        body.append("Product: ").append(product.getName()).append("\n");
        body.append("Lowest right now: ৳").append(String.format(Locale.US, "%,.0f", currentPrice)).append("\n");
        if (item.getPriceAtAdd() != null) {
            double diff = item.getPriceAtAdd() - currentPrice;
            body.append("When you added it: ৳").append(String.format(Locale.US, "%,.0f", item.getPriceAtAdd()));
            if (diff > 0) {
                body.append("  (you'd save ৳").append(String.format(Locale.US, "%,.0f", diff)).append(")");
            }
            body.append("\n");
        }
        body.append("\nSee live prices: https://damkemon.com/product/")
                .append(product.getId() == null ? product.getSlug() : product.getId())
                .append("\n\n— ").append(brand);

        try {
            mongo.getCollection(QUEUE).insertOne(new Document()
                    .append("to", to)
                    .append("from", fromAddress)
                    .append("subject", subject)
                    .append("body", body.toString())
                    .append("template", "price_drop")
                    .append("productId", product.getId())
                    .append("queuedAt", Instant.now())
                    .append("status", "pending"));
        } catch (Exception e) {
            log.warn("Could not enqueue price-drop email to {}: {}", to, e.getMessage());
        }

        log.info("Price drop alert queued — to={} product={} new=৳{}",
                to, product.getId(), String.format(Locale.US, "%,.0f", currentPrice));
    }
}
