package com.damKemon.dam.kemon.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

/**
 * Builds outbound affiliate URLs and resolves per-site partner parameters.
 *
 * <p>Each partner program defines its own query-string convention:
 * <ul>
 *   <li>Daraz uses {@code laz_trackid}.</li>
 *   <li>Pickaboo uses {@code utm_source} + a {@code clickid} sub-param.</li>
 *   <li>Generic shops without partner programs get UTM tags so we can at
 *       least see referral volume in their own analytics.</li>
 * </ul>
 *
 * Configurable via env vars so we can flip a code per partner without a
 * redeploy ({@code AFFILIATE_DARAZ_ID}, etc.).
 */
@Service
public class AffiliateService {

    private final Map<String, String> codes = new HashMap<>();

    @Value("${affiliate.daraz.id:damkemon}")    private String darazId;
    @Value("${affiliate.pickaboo.id:damkemon}") private String pickabooId;
    @Value("${affiliate.othoba.id:damkemon}")   private String othobaId;
    @Value("${affiliate.startech.id:damkemon}") private String startechId;
    @Value("${affiliate.ryans.id:damkemon}")    private String ryansId;
    @Value("${affiliate.fallback.utm-source:damkemon}") private String fallbackUtm;

    /**
     * Append the right tracking parameter for the partner. {@code clickId}
     * is the unique key we'll later reconcile against the partner's
     * payout report.
     */
    public String decorate(String originalUrl, String siteSlug, String clickId) {
        if (originalUrl == null || originalUrl.isBlank()) return originalUrl;
        if (clickId == null) clickId = "0";

        Map<String, String> params = new HashMap<>();
        String partnerCode = codeFor(siteSlug);
        switch (siteSlug == null ? "" : siteSlug.toLowerCase()) {
            case "daraz" -> {
                params.put("laz_trackid", partnerCode + ":" + clickId);
                params.put("utm_source", "damkemon");
            }
            case "pickaboo" -> {
                params.put("utm_source", "damkemon");
                params.put("utm_medium", "price-comparison");
                params.put("clickid", clickId);
                params.put("affid", partnerCode);
            }
            case "othoba", "startech", "ryans", "bdshop", "priyoshop" -> {
                params.put("utm_source", "damkemon");
                params.put("utm_medium", "compare");
                params.put("utm_campaign", partnerCode);
                params.put("dk", clickId);
            }
            default -> {
                params.put("utm_source", fallbackUtm);
                params.put("utm_medium", "price-compare");
                params.put("dk", clickId);
            }
        }
        return appendParams(originalUrl, params);
    }

    private String codeFor(String siteSlug) {
        return switch (siteSlug == null ? "" : siteSlug.toLowerCase()) {
            case "daraz" -> darazId;
            case "pickaboo" -> pickabooId;
            case "othoba" -> othobaId;
            case "startech" -> startechId;
            case "ryans" -> ryansId;
            default -> fallbackUtm;
        };
    }

    private static String appendParams(String url, Map<String, String> params) {
        try {
            URI parsed = URI.create(url);
            StringBuilder qs = new StringBuilder(parsed.getRawQuery() == null ? "" : parsed.getRawQuery());
            for (Map.Entry<String, String> e : params.entrySet()) {
                if (qs.length() > 0) qs.append('&');
                qs.append(java.net.URLEncoder.encode(e.getKey(), java.nio.charset.StandardCharsets.UTF_8))
                  .append('=')
                  .append(java.net.URLEncoder.encode(e.getValue(), java.nio.charset.StandardCharsets.UTF_8));
            }
            String fragment = parsed.getRawFragment() == null ? "" : "#" + parsed.getRawFragment();
            URI rebuilt = new URI(parsed.getScheme(), parsed.getRawAuthority(),
                    parsed.getRawPath(), qs.toString(), null);
            return rebuilt + fragment;
        } catch (Exception e) {
            // If the URL is malformed, return it unchanged rather than 500'ing the redirect
            return url;
        }
    }
}
