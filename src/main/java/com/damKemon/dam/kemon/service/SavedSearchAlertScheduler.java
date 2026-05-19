package com.damKemon.dam.kemon.service;

import com.damKemon.dam.kemon.dto.SearchResponse;
import com.damKemon.dam.kemon.model.Product;
import com.damKemon.dam.kemon.model.SavedSearch;
import com.damKemon.dam.kemon.model.User;
import com.damKemon.dam.kemon.repository.SavedSearchRepository;
import com.damKemon.dam.kemon.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Compares every {@link SavedSearch} against the current catalog daily.
 * If the cheapest matching product is strictly cheaper than the
 * {@code lastSeenLowest} we recorded last time, we email the user the
 * drop. Then we update {@code lastSeenLowest} either way so the alert
 * doesn't loop on stale data.
 */
@Service
public class SavedSearchAlertScheduler {

    private static final Logger log = LoggerFactory.getLogger(SavedSearchAlertScheduler.class);

    private final SavedSearchRepository savedSearches;
    private final UserRepository users;
    private final CatalogSearchService searchService;
    private final JavaMailSender mailSender;

    @Value("${spring.mail.username:}")
    private String mailUsername;

    @Value("${auth.from-email:no-reply@damkemon.com}")
    private String fromEmail;

    public SavedSearchAlertScheduler(SavedSearchRepository savedSearches,
                                     UserRepository users,
                                     CatalogSearchService searchService,
                                     JavaMailSender mailSender) {
        this.savedSearches = savedSearches;
        this.users = users;
        this.searchService = searchService;
        this.mailSender = mailSender;
    }

    @Scheduled(cron = "${saved-search.alert-cron:0 30 5 * * *}")
    public void run() {
        log.info("SavedSearchAlerts: starting daily run");
        List<SavedSearch> all;
        try { all = savedSearches.findAll(); }
        catch (DataAccessException e) {
            log.warn("SavedSearchAlerts: cannot list saved searches ({})", e.getMessage());
            return;
        }

        int notified = 0;
        for (SavedSearch s : all) {
            try {
                if (s.getQuery() == null || s.getQuery().isBlank()) continue;
                SearchResponse resp = searchService.search(s.getQuery());
                List<Product> hits = resp.getProducts();
                if (hits == null || hits.isEmpty()) continue;

                Product cheapest = hits.stream()
                        .filter(p -> p.getLowestPrice() != null && p.getLowestPrice() > 0)
                        .min((a, b) -> Double.compare(a.getLowestPrice(), b.getLowestPrice()))
                        .orElse(null);
                if (cheapest == null) continue;
                double currentLow = cheapest.getLowestPrice();

                Double seen = s.getLastSeenLowest();
                if (seen != null && currentLow < seen) {
                    // Send the drop alert
                    String toEmail = s.getNotifyEmail();
                    if ((toEmail == null || toEmail.isBlank()) && s.getUserId() != null) {
                        User u = users.findById(s.getUserId()).orElse(null);
                        if (u != null) toEmail = u.getEmail();
                    }
                    if (toEmail != null && !toEmail.isBlank()) {
                        sendDropAlert(toEmail, s.getQuery(), cheapest, seen, currentLow);
                        s.setLastNotifiedAt(LocalDateTime.now());
                        notified++;
                    }
                }
                s.setLastSeenLowest(currentLow);
                savedSearches.save(s);
            } catch (Exception e) {
                log.debug("SavedSearchAlerts: search '{}' failed: {}", s.getQuery(), e.getMessage());
            }
        }
        log.info("SavedSearchAlerts: done — {} alerts sent across {} saved searches", notified, all.size());
    }

    private void sendDropAlert(String to, String query, Product p, double from, double now) {
        if (mailUsername == null || mailUsername.isBlank()) {
            log.info("SavedSearchAlerts: would email {} — '{}' dropped ৳{} → ৳{} ({})",
                    to, query, from, now, p.getName());
            return;
        }
        try {
            SimpleMailMessage msg = new SimpleMailMessage();
            msg.setFrom(fromEmail);
            msg.setTo(to);
            msg.setSubject("Price drop on \"" + query + "\"");
            msg.setText(
                    "Hi,\n\n" +
                    "Your saved search \"" + query + "\" just got a fresh price drop.\n\n" +
                    p.getName() + "\n" +
                    "  Previous low: ৳" + (long) from + "\n" +
                    "  New low: ৳" + (long) now + "\n\n" +
                    "Browse it on Dam Kemon to see the seller.\n\n" +
                    "— Dam Kemon"
            );
            mailSender.send(msg);
        } catch (Exception e) {
            log.warn("SavedSearchAlerts: mail send failed for {}: {}", to, e.getMessage());
        }
    }
}
