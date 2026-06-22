package com.damKemon.dam.kemon.controller;

import com.damKemon.dam.kemon.model.RequestLog;
import com.damKemon.dam.kemon.service.AdminAnalyticsService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * The operator's traffic console. Everything is behind {@code /api/admin/} so it
 * inherits the JWT / X-Admin-Key gate, and is deliberately NOT written into
 * {@code request_log} (the {@code RequestLogFilter} skips this prefix) so that
 * reading the analytics doesn't pollute the analytics.
 */
@RestController
@RequestMapping("/api/admin/analytics")
public class AdminAnalyticsController {

    private final AdminAnalyticsService analytics;

    public AdminAnalyticsController(AdminAnalyticsService analytics) {
        this.analytics = analytics;
    }

    /** Today's headline counters + live "active now". */
    @GetMapping("/overview")
    public ResponseEntity<Map<String, Object>> overview() {
        return ResponseEntity.ok(analytics.overview());
    }

    /** Most-searched terms over the window. */
    @GetMapping("/top-searches")
    public ResponseEntity<List<Map<String, Object>>> topSearches(
            @RequestParam(value = "days", defaultValue = "7") int days,
            @RequestParam(value = "limit", defaultValue = "25") int limit) {
        return ResponseEntity.ok(analytics.topSearches(clampDays(days), clamp(limit, 1, 200)));
    }

    /** 24-hour histogram + the identified peak hour and what people search then. */
    @GetMapping("/hourly")
    public ResponseEntity<Map<String, Object>> hourly(
            @RequestParam(value = "days", defaultValue = "7") int days) {
        return ResponseEntity.ok(analytics.hourly(clampDays(days)));
    }

    /** Per-day unique visitors / searches / page views / requests time series. */
    @GetMapping("/daily-users")
    public ResponseEntity<List<Map<String, Object>>> dailyUsers(
            @RequestParam(value = "days", defaultValue = "14") int days) {
        return ResponseEntity.ok(analytics.dailyUsers(clamp(days, 1, 90)));
    }

    /** Top client IPs by request volume, with last-seen + device + reach. */
    @GetMapping("/top-ips")
    public ResponseEntity<List<Map<String, Object>>> topIps(
            @RequestParam(value = "days", defaultValue = "7") int days,
            @RequestParam(value = "limit", defaultValue = "25") int limit) {
        return ResponseEntity.ok(analytics.topIps(clampDays(days), clamp(limit, 1, 200)));
    }

    /** Busiest endpoints by hit count, with average latency. */
    @GetMapping("/top-paths")
    public ResponseEntity<List<Map<String, Object>>> topPaths(
            @RequestParam(value = "days", defaultValue = "7") int days,
            @RequestParam(value = "limit", defaultValue = "25") int limit) {
        return ResponseEntity.ok(analytics.topPaths(clampDays(days), clamp(limit, 1, 200)));
    }

    /** Tail of the raw "every step" request log. */
    @GetMapping("/requests")
    public ResponseEntity<List<RequestLog>> requests(
            @RequestParam(value = "limit", defaultValue = "100") int limit) {
        return ResponseEntity.ok(analytics.recentRequests(clamp(limit, 1, 500)));
    }

    // ── Outbound-click intelligence (affiliate_clicks) ──

    /** Which shop is clicked most for which category — shops ranked within each category. */
    @GetMapping("/shop-clicks-by-category")
    public ResponseEntity<List<Map<String, Object>>> shopClicksByCategory(
            @RequestParam(value = "days", defaultValue = "7") int days,
            @RequestParam(value = "categories", defaultValue = "12") int categories,
            @RequestParam(value = "shops", defaultValue = "5") int shops) {
        return ResponseEntity.ok(analytics.shopClicksByCategory(
                clampDays(days), clamp(categories, 1, 50), clamp(shops, 1, 20)));
    }

    /** Shops ranked by outbound clicks — who we send the most traffic to. */
    @GetMapping("/top-shops")
    public ResponseEntity<List<Map<String, Object>>> topShops(
            @RequestParam(value = "days", defaultValue = "7") int days,
            @RequestParam(value = "limit", defaultValue = "25") int limit) {
        return ResponseEntity.ok(analytics.topShops(clampDays(days), clamp(limit, 1, 200)));
    }

    /** Products ranked by outbound clicks — what shoppers click out to buy. */
    @GetMapping("/top-products")
    public ResponseEntity<List<Map<String, Object>>> topProducts(
            @RequestParam(value = "days", defaultValue = "7") int days,
            @RequestParam(value = "limit", defaultValue = "25") int limit) {
        return ResponseEntity.ok(analytics.topClickedProducts(clampDays(days), clamp(limit, 1, 200)));
    }

    /** Search terms that drove the most outbound clicks (search → click attribution). */
    @GetMapping("/top-converting-searches")
    public ResponseEntity<List<Map<String, Object>>> topConvertingSearches(
            @RequestParam(value = "days", defaultValue = "7") int days,
            @RequestParam(value = "limit", defaultValue = "25") int limit) {
        return ResponseEntity.ok(analytics.topConvertingSearches(clampDays(days), clamp(limit, 1, 200)));
    }

    /** Funnel: searches → product views → outbound clicks, with conversion rates. */
    @GetMapping("/funnel")
    public ResponseEntity<Map<String, Object>> funnel(
            @RequestParam(value = "days", defaultValue = "7") int days) {
        return ResponseEntity.ok(analytics.clickFunnel(clampDays(days)));
    }

    // ── Search result intelligence ──

    /** Which shops show first in search results (and how often they appear at all). */
    @GetMapping("/result-shops")
    public ResponseEntity<List<Map<String, Object>>> resultShops(
            @RequestParam(value = "days", defaultValue = "7") int days,
            @RequestParam(value = "limit", defaultValue = "25") int limit) {
        return ResponseEntity.ok(analytics.topResultShops(clampDays(days), clamp(limit, 1, 200)));
    }

    /** Searches that returned nothing — unmet demand / catalog gaps. */
    @GetMapping("/zero-result-searches")
    public ResponseEntity<List<Map<String, Object>>> zeroResultSearches(
            @RequestParam(value = "days", defaultValue = "7") int days,
            @RequestParam(value = "limit", defaultValue = "25") int limit) {
        return ResponseEntity.ok(analytics.zeroResultSearches(clampDays(days), clamp(limit, 1, 200)));
    }

    /** Shop price-win rate: how often each shop holds the cheapest offer where it appears. */
    @GetMapping("/shop-price-wins")
    public ResponseEntity<List<Map<String, Object>>> shopPriceWins(
            @RequestParam(value = "limit", defaultValue = "25") int limit) {
        return ResponseEntity.ok(analytics.shopPriceWins(clamp(limit, 1, 200)));
    }

    private static int clampDays(int d) {
        return clamp(d, 1, 90);
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(v, max));
    }
}
