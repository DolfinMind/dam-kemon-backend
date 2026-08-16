package com.damKemon.dam.kemon.service;

import com.damKemon.dam.kemon.config.AppRole;
import com.damKemon.dam.kemon.model.PriceAlertNotification;
import com.damKemon.dam.kemon.model.Product;
import com.damKemon.dam.kemon.model.User;
import com.damKemon.dam.kemon.model.WishlistItem;
import com.damKemon.dam.kemon.repository.PriceAlertNotificationRepository;
import com.damKemon.dam.kemon.repository.ProductRepository;
import com.damKemon.dam.kemon.repository.UserRepository;
import com.damKemon.dam.kemon.repository.WishlistItemRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Hourly scan over every alert-enabled wishlist item. For each, compare
 * the user's threshold against the product's current {@code lowestPrice}
 * and, if crossed, persist a {@link PriceAlertNotification} and ask
 * {@link EmailNotifier} to send the email.
 *
 * <p>Two safeguards prevent notification spam:
 * <ul>
 *   <li>Same-day debounce: if an alert for the same (user, product) fired
 *       in the last 24h, we skip.</li>
 *   <li>Stair-step debounce: we only re-alert when the new lowest is
 *       <em>further</em> below the previously notified price by at least
 *       2% — otherwise small daily flutter would re-fire repeatedly.</li>
 * </ul>
 *
 * Schedule defaults to every hour on the half-hour ({@code 0 30 * * * *}).
 * Disable with {@code price-alerts.enabled=false}.
 */
@Service
public class PriceAlertScheduler {

    private static final Logger log = LoggerFactory.getLogger(PriceAlertScheduler.class);

    /** When alertsEnabled but no explicit % set, this is the default trigger. */
    private static final double DEFAULT_DROP_PCT = 0.10;
    /** Stair-step: re-alert only if the new low is this much below the last alert price. */
    private static final double RE_ALERT_FLOOR_PCT = 0.05;

    private final WishlistItemRepository wishlist;
    private final ProductRepository products;
    private final UserRepository users;
    private final PriceAlertNotificationRepository notifications;
    private final EmailNotifier emailNotifier;
    private final AppRole appRole;

    @Value("${price-alerts.enabled:false}")
    private boolean enabled;
    @Value("${price-alerts.max-age-hours:30}")
    private int maxAgeHours;

    public PriceAlertScheduler(WishlistItemRepository wishlist,
                               ProductRepository products,
                               UserRepository users,
                               PriceAlertNotificationRepository notifications,
                               EmailNotifier emailNotifier,
                               AppRole appRole) {
        this.wishlist = wishlist;
        this.products = products;
        this.users = users;
        this.notifications = notifications;
        this.emailNotifier = emailNotifier;
        this.appRole = appRole;
    }

    @Scheduled(cron = "${price-alerts.cron:0 30 * * * *}")
    public void runAlertScan() {
        // Web role only: this cron used to fire on BOTH JVMs (web + worker),
        // double-scanning every hour — the 24h debounce hid most of it.
        if (!enabled || !appRole.isWeb()) return;
        retryDueAlerts();
        long t0 = System.nanoTime();
        int scanned = 0, fired = 0;
        try {
            for (WishlistItem w : wishlist.findAll()) {
                scanned++;
                if (processOne(w)) fired++;
            }
        } catch (Exception e) {
            log.warn("PriceAlertScheduler aborted partway: {}", e.getMessage());
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        log.info("Price alert scan — {} wishlist items, {} alerts fired in {} ms",
                scanned, fired, ms);
    }

    private boolean processOne(WishlistItem w) {
        if (!Boolean.TRUE.equals(w.getAlertsEnabled())) return false;
        if (w.getProductId() == null || w.getUserId() == null) return false;

        Product p = products.findById(w.getProductId()).orElse(null);
        if (p == null || p.getLowestPrice() == null || p.getLastScraped() == null
                || p.getLastScraped().isBefore(LocalDateTime.now().minusHours(maxAgeHours))) return false;

        double current = p.getLowestPrice();
        Double prevSeen = w.getLastSeenLowest() != null ? w.getLastSeenLowest() : w.getPriceAtAdd();

        boolean hitTarget = w.getTargetPrice() != null && current <= w.getTargetPrice();
        double dropPct = w.getAlertOnDropPercent() != null ? w.getAlertOnDropPercent() : DEFAULT_DROP_PCT;
        boolean droppedEnough = w.getPriceAtAdd() != null
                && current <= w.getPriceAtAdd() * (1 - dropPct);

        if (!hitTarget && !droppedEnough) {
            // No crossing — just refresh the seen-lowest so future drops compare correctly
            if (prevSeen == null || current < prevSeen) {
                w.setLastSeenLowest(current);
                safeSave(w);
            }
            return false;
        }

        // Debounce: skip if we fired in the last 72h
        LocalDateTime since = LocalDateTime.now().minusHours(72);
        if (notifications.findByUserIdAndProductIdAndCreatedAtAfter(w.getUserId(), w.getProductId(), since)
                .stream().anyMatch(n -> "accepted".equals(n.getDeliveryState()) || "inapp".equals(n.getDeliveryState()))) {
            return false;
        }

        // Stair-step: if we notified at, say, ৳90k, don't re-fire at ৳89.5k — wait for ৳88k.
        Double lastNotifiedPrice = lastNotifiedPriceFor(w.getUserId(), w.getProductId());
        if (lastNotifiedPrice != null && current > lastNotifiedPrice * (1 - RE_ALERT_FLOOR_PCT)) {
            return false;
        }

        Optional<User> user = users.findById(w.getUserId());
        String channel = w.getNotifyChannel() == null ? "email" : w.getNotifyChannel();
        boolean emailable = user.isPresent() && user.get().getEmail() != null && Boolean.TRUE.equals(user.get().getEmailVerified());
        // Verification is required for the promised email and must not consume this crossing.
        if ("email".equals(channel) && !emailable) return false;

        PriceAlertNotification note = PriceAlertNotification.builder()
                .userId(w.getUserId())
                .productId(p.getId())
                .productName(p.getName())
                .productImageUrl(p.getImageUrl())
                .priceAtAdd(w.getPriceAtAdd())
                .previousPrice(prevSeen)
                .currentPrice(current)
                .reason(hitTarget ? "hit_target" : "drop_pct")
                .deliveryKey(w.getUserId() + ":" + p.getId() + ":" + Math.round(current * 100) + ":" + (hitTarget ? "target" : "drop"))
                .deliveryState("pending")
                .deliveryAttempts(0)
                .nextDeliveryAttemptAt(LocalDateTime.now())
                .unread(true)
                .createdAt(LocalDateTime.now())
                .build();
        try { note = notifications.save(note); }
        catch (org.springframework.dao.DuplicateKeyException duplicate) { return false; }
        if ("email".equals(channel)) {
            boolean accepted = emailNotifier.sendPriceDropAlert(user.get().getEmail(), p, w, current, note.getDeliveryKey());
            note.setSentVia(accepted ? "email" : "failed");
            note.setDeliveryAttempts(1);
            note.setDeliveryState(accepted ? "accepted" : "failed");
            note.setNextDeliveryAttemptAt(accepted ? null : LocalDateTime.now().plusHours(1));
            notifications.save(note);
            if (!accepted) return false;
        } else { note.setSentVia("inapp"); note.setDeliveryState("inapp"); notifications.save(note); }
        w.setLastNotifiedAt(LocalDateTime.now()); w.setLastSeenLowest(current); safeSave(w); return true;
    }

    private void retryDueAlerts() {
        for (PriceAlertNotification n : notifications.findTop100ByDeliveryStateInAndNextDeliveryAttemptAtLessThanEqualOrderByCreatedAtAsc(
                java.util.List.of("failed", "pending"), LocalDateTime.now())) {
            if (n.getDeliveryAttempts() != null && n.getDeliveryAttempts() >= 3) {
                n.setDeliveryState("failed_terminal"); n.setNextDeliveryAttemptAt(null); notifications.save(n); continue;
            }
            User u = users.findById(n.getUserId()).orElse(null); Product p = products.findById(n.getProductId()).orElse(null);
            WishlistItem w = wishlist.findByUserIdAndProductId(n.getUserId(), n.getProductId()).orElse(null);
            if (u == null || p == null || w == null || !Boolean.TRUE.equals(u.getEmailVerified())
                    || !Boolean.TRUE.equals(w.getAlertsEnabled()) || (w.getNotifyChannel() != null && !"email".equals(w.getNotifyChannel()))
                    || p.getLastScraped() == null || p.getLastScraped().isBefore(LocalDateTime.now().minusHours(maxAgeHours))
                    || !stillQualifies(n, p, w)) {
                n.setDeliveryState("skipped"); n.setNextDeliveryAttemptAt(null); notifications.save(n); continue;
            }
            boolean accepted = emailNotifier.sendPriceDropAlert(u.getEmail(), p, w, n.getCurrentPrice(), n.getDeliveryKey());
            n.setDeliveryAttempts((n.getDeliveryAttempts() == null ? 0 : n.getDeliveryAttempts()) + 1);
            n.setDeliveryState(accepted ? "accepted" : (n.getDeliveryAttempts() >= 3 ? "failed_terminal" : "failed"));
            n.setSentVia(accepted ? "email" : "failed");
            n.setNextDeliveryAttemptAt(accepted || n.getDeliveryAttempts() >= 3 ? null : LocalDateTime.now().plusHours(1)); notifications.save(n);
            if (accepted) { w.setLastNotifiedAt(LocalDateTime.now()); safeSave(w); }
        }
    }

    private static boolean stillQualifies(PriceAlertNotification note, Product product, WishlistItem item) {
        if (product.getLowestPrice() == null) return false;
        if ("hit_target".equals(note.getReason())) return item.getTargetPrice() != null && product.getLowestPrice() <= item.getTargetPrice();
        double pct = item.getAlertOnDropPercent() != null ? item.getAlertOnDropPercent() : DEFAULT_DROP_PCT;
        return item.getPriceAtAdd() != null && product.getLowestPrice() <= item.getPriceAtAdd() * (1 - pct);
    }

    private Double lastNotifiedPriceFor(String userId, String productId) {
        LocalDateTime since = LocalDateTime.now().minusDays(30);
        return notifications.findByUserIdAndProductIdAndCreatedAtAfter(userId, productId, since)
                .stream()
                .map(PriceAlertNotification::getCurrentPrice)
                .filter(java.util.Objects::nonNull)
                .min(Double::compareTo)
                .orElse(null);
    }

    private void safeSave(WishlistItem w) {
        try { wishlist.save(w); }
        catch (Exception e) { log.debug("wishlist save failed (continuing): {}", e.getMessage()); }
    }
}
