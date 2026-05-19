package com.damKemon.dam.kemon.config;

import com.damKemon.dam.kemon.model.AuditLogEntry;
import com.damKemon.dam.kemon.repository.AuditLogRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

/**
 * Logs every admin endpoint hit into {@code audit_log}. Async-fired write —
 * the response is returned to the client unchanged even if the audit save
 * fails. TTL on the collection keeps it bounded at 90 days.
 */
@Component
public class AuditLogFilter extends OncePerRequestFilter {

    private final AuditLogRepository repo;

    public AuditLogFilter(AuditLogRepository repo) {
        this.repo = repo;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        chain.doFilter(req, res);
        String path = req.getRequestURI();
        if (path == null || !path.startsWith("/api/admin/")) return;

        try {
            String actor;
            Object userId = req.getAttribute("authUserId");
            if (userId != null) actor = "jwt:" + userId;
            else if (req.getHeader("X-Admin-Key") != null) actor = "admin-key";
            else actor = "anon";

            AuditLogEntry entry = AuditLogEntry.builder()
                    .method(req.getMethod())
                    .path(path)
                    .status(res.getStatus())
                    .actor(actor)
                    .ipHash(hashIp(req))
                    .ts(Instant.now())
                    .build();
            repo.save(entry);
        } catch (DataAccessException ignored) {
            /* audit is best-effort */
        } catch (Exception ignored) {
            /* never break the response on logging */
        }
    }

    private static String hashIp(HttpServletRequest req) {
        String ip;
        String fwd = req.getHeader("X-Forwarded-For");
        if (fwd != null && !fwd.isBlank()) {
            int c = fwd.indexOf(',');
            ip = (c < 0 ? fwd : fwd.substring(0, c)).trim();
        } else {
            ip = req.getRemoteAddr();
        }
        if (ip == null) return null;
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(ip.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(h).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            return null;
        }
    }
}
