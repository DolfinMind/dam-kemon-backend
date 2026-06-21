package com.damKemon.dam.kemon.controller;

import com.damKemon.dam.kemon.model.Feedback;
import com.damKemon.dam.kemon.model.NewsletterSubscriber;
import com.damKemon.dam.kemon.repository.FeedbackRepository;
import com.damKemon.dam.kemon.repository.NewsletterSubscriberRepository;
import com.damKemon.dam.kemon.service.ResendService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class EngagementController {

    private final NewsletterSubscriberRepository newsletterRepo;
    private final FeedbackRepository feedbackRepo;
    private final ResendService resendService;

    /** Recipient for the public "Contact us" / feedback form. Configured via
     *  USER_FEEDBACK_EMAIL (see application.yml feedback.notify-email); falls
     *  back to the Resend from-address when unset. */
    @Value("${feedback.notify-email:${resend.from-email:support@damkemon.com}}")
    private String feedbackEmail;

    @Autowired
    public EngagementController(NewsletterSubscriberRepository newsletterRepo, 
                              FeedbackRepository feedbackRepo, 
                              ResendService resendService) {
        this.newsletterRepo = newsletterRepo;
        this.feedbackRepo = feedbackRepo;
        this.resendService = resendService;
    }

    @PostMapping("/newsletter")
    public ResponseEntity<?> subscribeNewsletter(@RequestBody Map<String, String> payload) {
        String email = payload.get("email");
        if (email == null || email.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Email is required"));
        }

        email = email.trim().toLowerCase();

        // Check if already subscribed
        if (newsletterRepo.findByEmail(email).isEmpty()) {
            NewsletterSubscriber sub = new NewsletterSubscriber();
            sub.setEmail(email);
            sub.setSubscribedAt(Instant.now());
            newsletterRepo.save(sub);

            // Send welcome email
            String subject = "Welcome to DamKemon!";
            String htmlContent = "<div style=\"font-family: sans-serif; max-width: 600px; margin: 0 auto;\">"
                    + "<h2>Welcome to DamKemon!</h2>"
                    + "<p>Thank you for subscribing to our newsletter. You'll be the first to know about:</p>"
                    + "<ul>"
                    + "<li>Major price drops on trending tech.</li>"
                    + "<li>New features and scam-risk updates.</li>"
                    + "<li>Insights into the Bangladesh e-commerce landscape.</li>"
                    + "</ul>"
                    + "<p>Stay tuned for the smartest way to shop online!</p>"
                    + "<p>- The DamKemon Team</p>"
                    + "</div>";

            resendService.sendEmail(email, subject, htmlContent);
        }

        return ResponseEntity.ok(Map.of("success", true, "message", "Subscribed successfully"));
    }

    /** One-click unsubscribe linked from every newsletter email. Idempotent —
     *  removing an already-removed address still returns the friendly page. */
    @GetMapping("/newsletter/unsubscribe")
    public ResponseEntity<String> unsubscribeNewsletter(@RequestParam("email") String email) {
        if (email != null && !email.isBlank()) {
            newsletterRepo.findByEmail(email.trim().toLowerCase()).ifPresent(newsletterRepo::delete);
        }
        String html = "<!doctype html><html><body style=\"font-family:-apple-system,Segoe UI,Roboto,sans-serif;"
                + "background:#f6f5f1;margin:0;\"><div style=\"max-width:480px;margin:64px auto;background:#fff;"
                + "border:1px solid #ecebe6;border-radius:16px;padding:32px;text-align:center;\">"
                + "<div style=\"font-size:20px;font-weight:800;color:#15131a;\">You're unsubscribed</div>"
                + "<p style=\"color:#6b6b6b;font-size:14px;line-height:1.6;\">You won't receive the Damkemon weekly anymore. "
                + "Changed your mind? You can re-subscribe anytime on "
                + "<a href=\"https://damkemon.com\" style=\"color:#15131a;\">damkemon.com</a>.</p></div></body></html>";
        return ResponseEntity.ok().header("Content-Type", "text/html; charset=utf-8").body(html);
    }

    @PostMapping("/feedback")
    public ResponseEntity<?> submitFeedback(@RequestBody Map<String, String> payload) {
        String name = payload.get("name");
        String email = payload.get("email");
        String message = payload.get("message");

        if (email == null || email.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Email is required"));
        }
        if (message == null || message.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Message is required"));
        }

        Feedback feedback = new Feedback();
        feedback.setName(name);
        feedback.setEmail(email);
        feedback.setMessage(message);
        feedback.setSubmittedAt(Instant.now());
        feedbackRepo.save(feedback);

        // Notify support
        String subject = "New Feedback from " + (name != null && !name.isEmpty() ? name : "Anonymous");
        String htmlContent = "<div style=\"font-family: sans-serif;\">"
                + "<h3>New Feedback Submission</h3>"
                + "<p><strong>Name:</strong> " + (name != null ? name : "N/A") + "</p>"
                + "<p><strong>Email:</strong> " + (email != null ? email : "N/A") + "</p>"
                + "<p><strong>Message:</strong></p>"
                + "<blockquote style=\"border-left: 4px solid #ccc; padding-left: 10px;\">" + message.replace("\n", "<br>") + "</blockquote>"
                + "</div>";

        resendService.sendEmail(feedbackEmail, subject, htmlContent);

        return ResponseEntity.ok(Map.of("success", true, "message", "Feedback submitted"));
    }
}
