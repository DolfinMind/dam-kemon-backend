package com.damKemon.dam.kemon.payment;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice(assignableTypes = {PaymentController.class, PaymentAdminController.class})
@ConditionalOnProperty(name = "payments.enabled", havingValue = "true")
public class PaymentErrorHandler {
    @ExceptionHandler(PaymentException.class)
    ResponseEntity<Map<String, String>> payment(PaymentException e) {
        return ResponseEntity.status(e.status()).cacheControl(CacheControl.noStore())
                .body(Map.of("error", e.code(), "message", e.getMessage()));
    }

    @ExceptionHandler({MissingRequestHeaderException.class, HttpMessageNotReadableException.class})
    ResponseEntity<Map<String, String>> malformed(Exception ignored) {
        return ResponseEntity.badRequest().cacheControl(CacheControl.noStore())
                .body(Map.of("error", "invalid_request", "message", "Required payment request data is missing or malformed"));
    }
}
