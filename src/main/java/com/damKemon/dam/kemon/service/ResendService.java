package com.damKemon.dam.kemon.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class ResendService {
    private static final Logger logger = LoggerFactory.getLogger(ResendService.class);
    private static final String RESEND_API_URL = "https://api.resend.com/emails";

    @Value("${resend.api-key:}")
    private String apiKey;

    @Value("${resend.from-email:support@damkemon.com}")
    private String fromEmail;

    @Value("${resend.from-name:DolfinMind}")
    private String fromName;

    private final RestTemplate restTemplate = new RestTemplate();

    public void sendEmail(String to, String subject, String htmlContent) {
        if (apiKey == null || apiKey.isEmpty()) {
            logger.warn("Resend API key is not configured. Email to {} was not sent.", to);
            return;
        }

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey);

            Map<String, Object> body = new HashMap<>();
            body.put("from", fromName + " <" + fromEmail + ">");
            body.put("to", List.of(to));
            body.put("subject", subject);
            body.put("html", htmlContent);

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
            restTemplate.postForObject(RESEND_API_URL, request, String.class);
            logger.info("Successfully sent email via Resend to {}", to);
        } catch (Exception e) {
            logger.error("Failed to send email via Resend to {}: {}", to, e.getMessage());
        }
    }
}
