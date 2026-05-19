package com.damKemon.dam.kemon.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Sends the magic-link email. If {@code spring.mail.host} isn't configured
 * (the default in dev), we log the link to stdout instead of failing.
 *
 * <p>Outbound mail is best-effort and {@code @Async} so it never blocks the
 * sign-in request. If SMTP is unreachable, the user sees the same "check
 * your email" UI — they can hit "resend" if no email arrives.
 */
@Service
public class MagicLinkMailer {

    private static final Logger log = LoggerFactory.getLogger(MagicLinkMailer.class);

    private final JavaMailSender mailSender;

    @Value("${spring.mail.username:}")
    private String mailUsername;

    @Value("${auth.from-email:no-reply@damkemon.com}")
    private String fromEmail;

    @Value("${auth.web-url:http://localhost:5173}")
    private String webUrl;

    public MagicLinkMailer(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    @Async
    public void send(String to, String rawToken) {
        String link = buildLink(to, rawToken);

        if (mailUsername == null || mailUsername.isBlank()) {
            log.info("=========================================================");
            log.info("  Magic link for {}:", to);
            log.info("  {}", link);
            log.info("  (SMTP not configured — copy this URL into the browser)");
            log.info("=========================================================");
            return;
        }

        try {
            SimpleMailMessage msg = new SimpleMailMessage();
            msg.setFrom(fromEmail);
            msg.setTo(to);
            msg.setSubject("Sign in to Dam Kemon");
            msg.setText(
                    "Hi,\n\n" +
                    "Click the link below to sign in to Dam Kemon. " +
                    "The link expires in 15 minutes and can only be used once.\n\n" +
                    link + "\n\n" +
                    "If you didn't request this, you can safely ignore this email.\n\n" +
                    "— Dam Kemon"
            );
            mailSender.send(msg);
            log.info("MagicLinkMailer: sent sign-in email to {}", to);
        } catch (Exception e) {
            log.warn("MagicLinkMailer: send failed for {}: {} (link still works if user has it)",
                    to, e.getMessage());
        }
    }

    private String buildLink(String email, String rawToken) {
        String e = URLEncoder.encode(email, StandardCharsets.UTF_8);
        String t = URLEncoder.encode(rawToken, StandardCharsets.UTF_8);
        return webUrl.replaceAll("/$", "") + "/auth/verify?email=" + e + "&token=" + t;
    }
}
