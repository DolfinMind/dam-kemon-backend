package com.damKemon.dam.kemon.service;

import com.damKemon.dam.kemon.model.Shop;
import com.damKemon.dam.kemon.model.Shop.RunStat;
import com.damKemon.dam.kemon.repository.ShopRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Rolls per-run outcomes into a {@code health} signal on every shop and
 * decides whether a shop should be auto-disabled or queued for retry.
 *
 * <p>Score is a function of the last 7 runs:
 * <ul>
 *   <li>≥4 successful runs (count &gt; 0) → <b>active</b></li>
 *   <li>1–3 successful runs → <b>degraded</b> (still in rotation, surfaced
 *       to operators)</li>
 *   <li>0 successful runs → <b>dormant</b></li>
 * </ul>
 *
 * <p>Auto-disable is intentionally conservative: a shop flips to "blocked"
 * only after returning 0 products across the FULL window (~a week of nightly
 * runs), so transient timeouts or a one-off bot-block never disable a healthy
 * shop. {@code consecutiveFailures} still drives the cheaper same-night retry.
 */
@Service
public class ShopHealthService {

    private static final Logger log = LoggerFactory.getLogger(ShopHealthService.class);
    private static final int WINDOW = 7;

    private final ShopRepository shopRepository;

    public ShopHealthService(ShopRepository shopRepository) {
        this.shopRepository = shopRepository;
    }

    /**
     * Append a run outcome to the sliding window and recompute the health
     * signal. The caller is expected to {@code save} the shop after this
     * (it's safe to call save twice — we don't here, to avoid an extra
     * round-trip in the hot indexer loop).
     */
    public void recordRun(Shop shop, int count, String error) {
        if (shop.getRecentRuns() == null) shop.setRecentRuns(new ArrayList<>());
        List<RunStat> runs = shop.getRecentRuns();
        runs.add(0, RunStat.builder()
                .at(LocalDateTime.now())
                .count(count)
                .error(error)
                .build());
        while (runs.size() > WINDOW) runs.remove(runs.size() - 1);

        long successes = runs.stream().filter(r -> r.getCount() != null && r.getCount() > 0).count();
        boolean lastFailed = error != null || count <= 0;

        int consecutive = shop.getConsecutiveFailures() == null ? 0 : shop.getConsecutiveFailures();
        if (lastFailed) {
            shop.setConsecutiveFailures(consecutive + 1);
            shop.setNeedsRetry(true);
        } else {
            shop.setConsecutiveFailures(0);
            shop.setNeedsRetry(false);
        }

        if (successes >= 4) shop.setHealth("active");
        else if (successes >= 1) shop.setHealth("degraded");
        else shop.setHealth("dormant");

        // Auto-disable only a genuinely dead shop: zero products across the FULL
        // window (~a week of nightly runs). The old "3 consecutive failures" rule
        // was far too twitchy — a couple of timeouts or a transient bot-block
        // permanently disabled healthy shops and decimated the active catalog.
        if (runs.size() >= WINDOW && successes == 0
                && "active".equals(shop.getStatus())) {
            log.warn("ShopHealth: auto-disabling shop {} — 0 products across last {} runs",
                    shop.getSlug(), runs.size());
            shop.setStatus("blocked");
        }
    }

    /** Shops that need a retry pass after the nightly run. */
    public List<Shop> shopsNeedingRetry() {
        try {
            return shopRepository.findAll().stream()
                    .filter(s -> Boolean.TRUE.equals(s.getNeedsRetry()))
                    .filter(s -> !"blocked".equals(s.getStatus()))
                    .toList();
        } catch (DataAccessException e) {
            return List.of();
        }
    }
}
