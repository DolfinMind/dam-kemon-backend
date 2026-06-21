package com.damKemon.dam.kemon.controller;

import com.damKemon.dam.kemon.service.HeadlineStatsService;
import com.damKemon.dam.kemon.service.HotDropsService;
import com.damKemon.dam.kemon.service.LiveStatsService;
import com.damKemon.dam.kemon.service.WorldCupService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Public-facing analytics readback. The homepage uses these to render the
 * "X users searching now" pill, the trending-searches strip, and the
 * "Hot drops" rail.
 */
@RestController
@RequestMapping("/api/stats")
public class StatsController {

    private final LiveStatsService liveStats;
    private final HotDropsService hotDrops;
    private final WorldCupService worldCup;
    private final HeadlineStatsService headlineStats;

    public StatsController(LiveStatsService liveStats, HotDropsService hotDrops, WorldCupService worldCup,
                          HeadlineStatsService headlineStats) {
        this.liveStats = liveStats;
        this.hotDrops = hotDrops;
        this.worldCup = worldCup;
        this.headlineStats = headlineStats;
    }

    /** Homepage social-proof headline figures: money saved this month, price
     *  comparisons today, price drops tracked this week. Real-derived,
     *  baseline-floored so the widgets always feel alive. */
    @GetMapping("/headline")
    public ResponseEntity<Map<String, Object>> headline() {
        return ResponseEntity.ok(headlineStats.headline());
    }

    @GetMapping("/live")
    public ResponseEntity<Map<String, Object>> live() {
        return ResponseEntity.ok(liveStats.liveCounters());
    }

    @GetMapping("/trending")
    public ResponseEntity<List<Map<String, Object>>> trending(
            @RequestParam(value = "limit", defaultValue = "10") int limit) {
        return ResponseEntity.ok(liveStats.trending(Math.max(1, Math.min(limit, 50))));
    }

    @GetMapping("/hot-drops")
    public ResponseEntity<List<Map<String, Object>>> hotDrops(
            @RequestParam(value = "limit", defaultValue = "12") int limit) {
        return ResponseEntity.ok(hotDrops.get(Math.max(1, Math.min(limit, 24))));
    }

    /** Seasonal World Cup 2026 rail: jerseys, flags, supporter gear. */
    @GetMapping("/world-cup")
    public ResponseEntity<List<Map<String, Object>>> worldCup(
            @RequestParam(value = "limit", defaultValue = "12") int limit) {
        return ResponseEntity.ok(worldCup.get(Math.max(1, Math.min(limit, 24))));
    }
}
