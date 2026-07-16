package com.damKemon.dam.kemon.config;

import com.damKemon.dam.kemon.service.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminGateFilterTest {

    private static final String INGEST = "/api/admin/catalog/ingest";

    @Test
    void allowsDirectIpv4AndIpv6LoopbackIngestWithoutCredentials() throws Exception {
        for (String remote : new String[]{"127.0.0.1", "::1", "0:0:0:0:0:0:0:1"}) {
            FilterResult result = filter(INGEST, remote, null, null);

            assertTrue(result.passed(), remote);
            assertEquals(200, result.response().getStatus(), remote);
        }
    }

    @Test
    void rejectsForwardedLoopbackIngestWithoutCredentials() throws Exception {
        FilterResult result = filter(INGEST, "127.0.0.1", "X-Forwarded-For", "203.0.113.10");

        assertFalse(result.passed());
        assertEquals(401, result.response().getStatus());
    }

    @Test
    void rejectsRemoteIngestWithoutCredentials() throws Exception {
        FilterResult result = filter(INGEST, "203.0.113.10", null, null);

        assertFalse(result.passed());
        assertEquals(401, result.response().getStatus());
    }

    @Test
    void keepsOtherAdminRoutesProtectedOnLoopback() throws Exception {
        FilterResult result = filter("/api/admin/index/run", "127.0.0.1", null, null);

        assertFalse(result.passed());
        assertEquals(401, result.response().getStatus());
    }

    @Test
    void allowsAdminBearerAfterJwtAuthenticationFilter() throws Exception {
        JwtService jwt = new JwtService("test-secret-long-enough-for-admin-jwt", 1);
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", "/api/admin/analytics/overview");
        request.addHeader("Authorization", "Bearer " + jwt.issue("u1", "admin@test", "ADMIN"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean passed = new AtomicBoolean();

        try {
            new JwtAuthFilter(jwt).doFilter(request, response, (req, res) ->
                    new SecurityConfig.AdminGateFilter("secret").doFilter(
                            req, res, (innerReq, innerRes) -> passed.set(true)));
        } finally {
            SecurityContextHolder.clearContext();
        }

        assertTrue(passed.get());
        assertEquals(200, response.getStatus());
    }

    private static FilterResult filter(String path, String remote, String header, String value)
            throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setRemoteAddr(remote);
        if (header != null) request.addHeader(header, value);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean passed = new AtomicBoolean();

        new SecurityConfig.AdminGateFilter("secret").doFilter(
                request, response, (req, res) -> passed.set(true));

        return new FilterResult(passed.get(), response);
    }

    private record FilterResult(boolean passed, MockHttpServletResponse response) {}
}
