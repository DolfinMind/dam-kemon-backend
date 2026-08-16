package com.damKemon.dam.kemon.util;

import java.util.regex.Pattern;

/**
 * Coarse traffic classification performed while the request IP is still
 * available. Only the result is persisted when raw-IP storage is disabled.
 */
public final class TrafficClassifier {

    public static final String LIKELY_HUMAN = "likely_human";
    public static final String KNOWN_BOT = "known_bot";
    public static final String SUSPECTED_BOT = "suspected_bot";
    public static final String UNCLASSIFIED = "unclassified";

    private static final Pattern BOT_UA = Pattern.compile(
            "bot|spider|crawl|curl|python|wget|scrapy|headless|phantom|slurp"
                    + "|facebookexternalhit|whatsapp|telegram|axios|okhttp|httpclient"
                    + "|java/|go-http|node-fetch|uptime|pingdom|monitor|lighthouse|pagespeed",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern OBSERVED_GOOGLE_RENDERER_IP = Pattern.compile("^66\\.249\\.");

    private TrafficClassifier() {}

    public static String classify(String userAgent, String ip) {
        if (isKnownBotUa(userAgent)) return KNOWN_BOT;
        if (ip != null && OBSERVED_GOOGLE_RENDERER_IP.matcher(ip).find()) return SUSPECTED_BOT;
        if (userAgent == null || userAgent.isBlank()) return UNCLASSIFIED;
        return LIKELY_HUMAN;
    }

    public static boolean isKnownBotUa(String userAgent) {
        return userAgent != null && BOT_UA.matcher(userAgent).find();
    }

    public static Pattern knownBotUserAgentPattern() {
        return BOT_UA;
    }

    public static Pattern suspectedRendererIpPattern() {
        return OBSERVED_GOOGLE_RENDERER_IP;
    }
}
