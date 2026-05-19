package com.damKemon.dam.kemon.config;

import com.damKemon.dam.kemon.service.JwtService;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Reads the {@code Authorization: Bearer ...} header on every request and
 * stamps an authenticated principal into the Spring SecurityContext when
 * the token verifies. Requests without a token continue anonymously —
 * downstream controllers gate authenticated-only routes themselves.
 */
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwt;

    public JwtAuthFilter(JwtService jwt) {
        this.jwt = jwt;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String header = req.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring("Bearer ".length()).trim();
            Claims c = jwt.parse(token);
            if (c != null) {
                String role = c.get("role", String.class);
                List<SimpleGrantedAuthority> auths = role == null
                        ? List.of()
                        : List.of(new SimpleGrantedAuthority("ROLE_" + role.toUpperCase()));
                UsernamePasswordAuthenticationToken auth =
                        new UsernamePasswordAuthenticationToken(c.getSubject(), null, auths);
                auth.setDetails(c);
                SecurityContextHolder.getContext().setAuthentication(auth);
                req.setAttribute("authUserId", c.getSubject());
                req.setAttribute("authUserEmail", c.get("email", String.class));
                req.setAttribute("authUserRole", role);
            }
        }
        chain.doFilter(req, res);
    }
}
