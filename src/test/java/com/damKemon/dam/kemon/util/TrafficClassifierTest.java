package com.damKemon.dam.kemon.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TrafficClassifierTest {

    private static final String CHROME =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/126 Mobile Safari/537.36";

    @Test
    void classifiesKnownSuspectedHumanAndUnknownTraffic() {
        assertEquals(TrafficClassifier.KNOWN_BOT,
                TrafficClassifier.classify("Googlebot/2.1 (+https://google.com/bot.html)", "1.2.3.4"));
        assertEquals(TrafficClassifier.SUSPECTED_BOT,
                TrafficClassifier.classify(CHROME, "66.249.66.1"));
        assertEquals(TrafficClassifier.LIKELY_HUMAN,
                TrafficClassifier.classify(CHROME, "203.0.113.9"));
        assertEquals(TrafficClassifier.UNCLASSIFIED,
                TrafficClassifier.classify(null, "203.0.113.9"));
    }
}
