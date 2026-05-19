package com.damKemon.dam.kemon.config;

import com.damKemon.dam.kemon.service.JwtService;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * Public endpoints stay open. {@code /api/admin/**} is gated behind either
 * a valid admin JWT (issued by the magic-link flow to users with role
 * {@code admin}) OR the legacy {@code X-Admin-Key} header. Either is
 * accepted so existing curl-based operator scripts continue to work.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    @Value("${admin.api-key:}")
    private String adminApiKey;

    @Value("${cors.allowed-origins:http://localhost:5173}")
    private String allowedOrigins;

    @Value("${ratelimit.capacity:60}")
    private long rateLimitCapacity;

    @Value("${ratelimit.refill-per-sec:1.0}")
    private double rateLimitRefillPerSec;

    @Bean
    public RateLimiter searchRateLimiter() {
        return new RateLimiter(rateLimitCapacity, rateLimitRefillPerSec);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   JwtService jwtService,
                                                   AuditLogFilter auditLog) throws Exception {
        if (adminApiKey == null || adminApiKey.isBlank()) {
            log.warn("ADMIN_API_KEY is not set — /api/admin/** endpoints accept any JWT-authenticated admin "
                    + "OR run open in dev. Set the env var before deploying.");
        } else {
            log.info("Admin API key is set ({} chars) — /api/admin/** requires either an admin JWT or X-Admin-Key.", adminApiKey.length());
        }

        http
            .csrf(csrf -> csrf.disable())
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
            .addFilterBefore(new RateLimitFilter(searchRateLimiter()),
                    UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(new JwtAuthFilter(jwtService),
                    UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(new AdminGateFilter(adminApiKey),
                    UsernamePasswordAuthenticationFilter.class)
            .addFilterAfter(auditLog, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        List<String> origins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
        configuration.setAllowedOrigins(origins);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setExposedHeaders(List.of("X-Admin-Key", "X-Anon-Id", "Authorization"));
        configuration.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    /**
     * Gate every request to /api/admin/** behind either:
     *   - a valid {@code X-Admin-Key} header matching {@code ADMIN_API_KEY}, OR
     *   - an admin-role JWT (set on the request by {@link JwtAuthFilter}).
     *
     * No-op when {@code adminApiKey} is blank AND no JWT is present (dev mode).
     */
    static class AdminGateFilter extends OncePerRequestFilter {
        private final String expectedKey;

        AdminGateFilter(String expectedKey) {
            this.expectedKey = expectedKey;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
                throws ServletException, IOException {
            String path = req.getRequestURI();
            if (path == null || !path.startsWith("/api/admin/")) {
                chain.doFilter(req, res);
                return;
            }

            // JWT path: a signed-in admin user passes through.
            Object role = req.getAttribute("authUserRole");
            if ("admin".equals(role)) {
                chain.doFilter(req, res);
                return;
            }

            // Legacy key path: existing operator scripts.
            if (expectedKey != null && !expectedKey.isBlank()) {
                String sent = req.getHeader("X-Admin-Key");
                if (sent != null && constantTimeEquals(sent, expectedKey)) {
                    chain.doFilter(req, res);
                    return;
                }
                res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                res.setContentType("application/json");
                res.getWriter().write("{\"error\":\"sign in as an admin or send X-Admin-Key\"}");
                return;
            }

            // Dev mode: no key set, no JWT — let it through but it's logged on boot.
            chain.doFilter(req, res);
        }

        private static boolean constantTimeEquals(String a, String b) {
            if (a == null || b == null || a.length() != b.length()) return false;
            int diff = 0;
            for (int i = 0; i < a.length(); i++) diff |= a.charAt(i) ^ b.charAt(i);
            return diff == 0;
        }
    }
}
