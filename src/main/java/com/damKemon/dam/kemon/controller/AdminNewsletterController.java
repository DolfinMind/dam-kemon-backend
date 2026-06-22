package com.damKemon.dam.kemon.controller;

import com.damKemon.dam.kemon.model.NewsletterSubscriber;
import com.damKemon.dam.kemon.repository.NewsletterSubscriberRepository;
import com.damKemon.dam.kemon.service.NewsletterService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/newsletter")
public class AdminNewsletterController {

    private final NewsletterSubscriberRepository subscriberRepository;
    private final NewsletterService newsletterService;

    public AdminNewsletterController(NewsletterSubscriberRepository subscriberRepository,
                                     NewsletterService newsletterService) {
        this.subscriberRepository = subscriberRepository;
        this.newsletterService = newsletterService;
    }

    @GetMapping("/analytics")
    public ResponseEntity<Map<String, Object>> getAnalytics() {
        long total = subscriberRepository.count();
        long last7Days = subscriberRepository.countBySubscribedAtAfter(Instant.now().minus(7, ChronoUnit.DAYS));
        long last30Days = subscriberRepository.countBySubscribedAtAfter(Instant.now().minus(30, ChronoUnit.DAYS));
        
        // Simple growth rate calculation (new in 30 days vs total before 30 days)
        long before30Days = total - last30Days;
        double growthRate = before30Days == 0 ? (last30Days > 0 ? 100.0 : 0.0) : ((double) last30Days / before30Days) * 100.0;

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("total", total);
        data.put("last7Days", last7Days);
        data.put("last30Days", last30Days);
        data.put("growthRate", Math.round(growthRate * 10.0) / 10.0);
        return ResponseEntity.ok(data);
    }

    @GetMapping("/subscribers")
    public ResponseEntity<Page<NewsletterSubscriber>> listSubscribers(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        Page<NewsletterSubscriber> result = subscriberRepository.findAll(
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "subscribedAt"))
        );
        return ResponseEntity.ok(result);
    }

    @PostMapping("/send")
    public ResponseEntity<Map<String, Object>> sendManual() {
        // Synchronous so the admin sees the real outcome (sent / failed / why-skipped)
        // instead of a fire-and-forget "triggered" that hid every failure.
        // ponytail: blocks for ~send-delay × subscribers; fine at this list size.
        // Move to a job-status poll if the list grows into the thousands.
        return ResponseEntity.ok(newsletterService.sendManual());
    }
}
