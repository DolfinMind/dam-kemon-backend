package com.damKemon.dam.kemon.util;

import jakarta.servlet.http.HttpServletRequest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Single source of truth for resolving the real client IP from behind a proxy
 * and for the stable SHA-256 IP hash. The same X-Forwarded-For parsing used to
 * be copy-pasted into AnalyticsService, AnalyticsController, SearchController
 * and AuditLogFilter.
 */
public final class ClientIp {

    private ClientIp() {}

    /** Real client IP: first hop of X-Forwarded-For, then X-Real-IP, then remoteAddr. */
    public static String of(HttpServletRequest req) {
        if (req == null) return null;
        String fwd = req.getHeader("X-Forwarded-For");
        if (fwd != null && !fwd.isBlank()) {
            int comma = fwd.indexOf(',');
            String first = (comma < 0 ? fwd : fwd.substring(0, comma)).trim();
            if (!first.isEmpty()) return first;
        }
        String real = req.getHeader("X-Real-IP");
        if (real != null && !real.isBlank()) return real.trim();
        return req.getRemoteAddr();
    }

    /** SHA-256 hex of the IP, truncated to 16 chars. Stable, not reversible. */
    public static String hash(String ip) {
        if (ip == null || ip.isBlank()) return null;
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(ip.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(h).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            return null;
        }
    }
}
