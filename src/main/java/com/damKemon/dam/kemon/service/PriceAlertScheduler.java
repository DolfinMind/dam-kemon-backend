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

    @Value("${price-alerts.enabled:true}")
    private boolean enabled;

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
        if (p == null || p.getLowestPrice() == null) return false;

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
        if (!notifications.findByUserIdAndProductIdAndCreatedAtAfter(
                w.getUserId(), w.getProductId(), since).isEmpty()) {
            return false;
        }

        // Stair-step: if we notified at, say, ৳90k, don't re-fire at ৳89.5k — wait for ৳88k.
        Double lastNotifiedPrice = lastNotifiedPriceFor(w.getUserId(), w.getProductId());
        if (lastNotifiedPrice != null && current > lastNotifiedPrice * (1 - RE_ALERT_FLOOR_PCT)) {
            return false;
        }

        PriceAlertNotification note = PriceAlertNotification.builder()
                .userId(w.getUserId())
                .productId(p.getId())
                .productName(p.getName())
                .productImageUrl(p.getImageUrl())
                .priceAtAdd(w.getPriceAtAdd())
                .previousPrice(prevSeen)
                .currentPrice(current)
                .reason(hitTarget ? "hit_target" : "drop_pct")
                .unread(true)
                .createdAt(LocalDateTime.now())
                .build();

        Optional<User> user = users.findById(w.getUserId());
        String channel = w.getNotifyChannel() == null ? "email" : w.getNotifyChannel();
        // Email only to proven inboxes: explicit false = fresh signup that never
        // clicked the verify link. Null (owner/legacy rows) counts as verified.
        boolean emailable = user.isPresent() && user.get().getEmail() != null
                && !Boolean.FALSE.equals(user.get().getEmailVerified());
        if ("email".equals(channel) && emailable) {
            emailNotifier.sendPriceDropAlert(user.get().getEmail(), p, w, current);
            note.setSentVia("email");
        } else {
            note.setSentVia("inapp");
        }

        try { notifications.save(note); } catch (Exception e) {
            log.warn("Failed to persist notification for {}: {}", w.getUserId(), e.getMessage());
        }

        w.setLastNotifiedAt(LocalDateTime.now());
        w.setLastSeenLowest(current);
        safeSave(w);
        return true;
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
