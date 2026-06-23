package com.damKemon.dam.kemon.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Round-robin pool of upstream proxy host:port pairs parsed from
 * {@code scraper.proxy-url} (comma-separated {@code host:port:user:pass}).
 * Credentials are handled JVM-wide by {@link ProxyBootstrap}'s Authenticator —
 * all entries share one credential pair — so callers only need the host:port to
 * set a per-request {@code Connection.proxy(host, port)} and spread load across
 * IPs (avoids the single-IP rate-limit that throttles deep Daraz harvests).
 */
@Component
public class ProxyPool {

    @Value("${scraper.proxy-url:}")
    private String proxyUrl;

    private final List<String[]> pool = new ArrayList<>();   // each = {host, port}
    private final AtomicInteger idx = new AtomicInteger();

    @PostConstruct
    void init() {
        if (proxyUrl == null || proxyUrl.isBlank()) return;
        for (String e : proxyUrl.split(",")) {
            String[] p = e.trim().split(":");
            if (p.length >= 2 && !p[0].isBlank()) pool.add(new String[]{p[0], p[1]});
        }
    }

    public boolean enabled() { return !pool.isEmpty(); }

    public int size() { return pool.size(); }

    /** Next host:port pair, round-robin; null when no pool configured. */
    public String[] next() {
        if (pool.isEmpty()) return null;
        return pool.get(Math.floorMod(idx.getAndIncrement(), pool.size()));
    }
}
