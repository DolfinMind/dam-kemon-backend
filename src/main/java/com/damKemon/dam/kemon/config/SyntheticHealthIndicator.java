package com.damKemon.dam.kemon.config;

import com.damKemon.dam.kemon.intelligence.TrigramSearchIndex;
import com.damKemon.dam.kemon.service.SyntheticMonitorService;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * {@code /actuator/health} exposes a {@code synthetic} component that
 * flips to {@code DOWN} as soon as the canary searches start returning
 * zero results. External monitors poll the health endpoint and alert.
 */
@Component("synthetic")
public class SyntheticHealthIndicator implements HealthIndicator {

    private final SyntheticMonitorService monitor;
    private final TrigramSearchIndex trigram;

    public SyntheticHealthIndicator(SyntheticMonitorService monitor, TrigramSearchIndex trigram) {
        this.monitor = monitor;
        this.trigram = trigram;
    }

    @Override
    public Health health() {
        Map<String, Object> trigramStatus = trigram.status();
        if (!trigram.isReady()) {
            return Health.down().withDetail("trigram", trigramStatus).build();
        }
        Map<String, Object> latest = monitor.latest();
        if (latest == null || latest.isEmpty()) {
            return Health.unknown()
                    .withDetail("note", "no run yet")
                    .withDetail("trigram", trigramStatus)
                    .build();
        }
        boolean ok = Boolean.TRUE.equals(latest.get("ok"));
        Health.Builder b = ok ? Health.up() : Health.down();
        latest.forEach(b::withDetail);
        return b.withDetail("trigram", trigramStatus).build();
    }
}
