package com.damKemon.dam.kemon.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.Authenticator;
import java.net.PasswordAuthentication;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Routes outbound HTTP(S) through an upstream authenticated proxy so IP-blocked
 * sources (Daraz, the JS mobile retailers) become reachable. All the high-value
 * harvesters use Jsoup → {@code HttpURLConnection}, which honours the standard
 * {@code http(s).proxyHost/Port} system properties, so setting them here proxies
 * every fetcher with zero per-harvester changes.
 *
 * <p>Config {@code scraper.proxy-url}: one or more {@code host:port:user:pass}
 * entries, comma-separated. A random entry is picked per process start, so each
 * worker pass (a fresh JVM) rotates across the pool. All entries are assumed to
 * share one credential pair (the Authenticator answers any PROXY challenge).
 */
@Component
public class ProxyBootstrap {

    private static final Logger log = LoggerFactory.getLogger(ProxyBootstrap.class);

    @Value("${scraper.proxy-url:}")
    private String proxyUrl;

    @PostConstruct
    void init() {
        if (proxyUrl == null || proxyUrl.isBlank()) return;
        String[] entries = proxyUrl.split(",");
        String entry = entries[ThreadLocalRandom.current().nextInt(entries.length)].trim();
        String[] p = entry.split(":");
        if (p.length < 2) { log.warn("ProxyBootstrap: malformed scraper.proxy-url entry '{}'", entry); return; }
        String host = p[0];
        String port = p[1];
        final String user = p.length > 2 ? p[2] : null;
        final String pass = p.length > 3 ? p[3] : null;

        // HTTPS goes through the proxy via a CONNECT tunnel; the JVM disables
        // Basic auth on tunnels by default, so clear that or proxy auth silently fails.
        System.setProperty("jdk.http.auth.tunneling.disabledSchemes", "");
        System.setProperty("http.proxyHost", host);
        System.setProperty("http.proxyPort", port);
        System.setProperty("https.proxyHost", host);
        System.setProperty("https.proxyPort", port);
        System.setProperty("http.nonProxyHosts", "localhost|127.0.0.1|0.0.0.0");

        if (user != null && pass != null) {
            Authenticator.setDefault(new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    return getRequestorType() == RequestorType.PROXY
                            ? new PasswordAuthentication(user, pass.toCharArray())
                            : null;
                }
            });
        }
        log.info("ProxyBootstrap: outbound HTTP(S) via proxy {}:{} (auth={}, pool={})",
                host, port, user != null, entries.length);
    }
}
