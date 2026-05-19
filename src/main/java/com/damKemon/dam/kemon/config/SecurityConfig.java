package com.damKemon.dam.kemon.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
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
 * Public endpoints stay open. The {@code /api/admin/**} surface (manual
 * indexer triggers, wipe-and-reindex, shop edits) is gated behind an
 * {@code X-Admin-Key} header that must match {@code ADMIN_API_KEY} from
 * the environment.
 *
 * <p>Local dev: leave {@code ADMIN_API_KEY} blank — admin endpoints stay
 * open but we WARN on boot so it's not silent.
 *
 * <p>Staging / production: set {@code ADMIN_API_KEY} to a random 32-char
 * value (use a secrets manager). Operators send it on every admin call:
 *
 * <pre>curl -H "X-Admin-Key: $ADMIN_API_KEY" -X POST https://api/admin/index/run</pre>
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
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        if (adminApiKey == null || adminApiKey.isBlank()) {
            log.warn("ADMIN_API_KEY is not set — /api/admin/** endpoints are PUBLIC. "
                    + "Set the env var before deploying to staging or production.");
        } else {
            log.info("Admin API key is set ({} chars) — /api/admin/** requires X-Admin-Key header.", adminApiKey.length());
        }

        http
            .csrf(csrf -> csrf.disable())
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
            .addFilterBefore(new RateLimitFilter(searchRateLimiter()),
                    UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(new AdminKeyFilter(adminApiKey),
                    UsernamePasswordAuthenticationFilter.class);
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
        configuration.setExposedHeaders(List.of("X-Admin-Key", "X-Anon-Id"));
        configuration.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    /**
     * Gates every request to /api/admin/** behind the {@code X-Admin-Key}
     * header. No-op when {@code adminApiKey} is blank (dev mode).
     */
    static class AdminKeyFilter extends OncePerRequestFilter {
        private final String expectedKey;

        AdminKeyFilter(String expectedKey) {
            this.expectedKey = expectedKey;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
                throws ServletException, IOException {
            String path = req.getRequestURI();
            if (path != null && path.startsWith("/api/admin/") && expectedKey != null && !expectedKey.isBlank()) {
                String sent = req.getHeader("X-Admin-Key");
                if (sent == null || !constantTimeEquals(sent, expectedKey)) {
                    res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    res.setContentType("application/json");
                    res.getWriter().write("{\"error\":\"missing or invalid X-Admin-Key header\"}");
                    return;
                }
            }
            chain.doFilter(req, res);
        }

        /** Constant-time compare to avoid trivial timing attacks. */
        private static boolean constantTimeEquals(String a, String b) {
            if (a == null || b == null || a.length() != b.length()) return false;
            int diff = 0;
            for (int i = 0; i < a.length(); i++) diff |= a.charAt(i) ^ b.charAt(i);
            return diff == 0;
        }
    }
}
