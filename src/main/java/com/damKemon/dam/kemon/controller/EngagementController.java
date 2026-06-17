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

    @Value("${resend.from-email:support@damkemon.com}")
    private String supportEmail;

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

        resendService.sendEmail(supportEmail, subject, htmlContent);

        return ResponseEntity.ok(Map.of("success", true, "message", "Feedback submitted"));
    }
}
