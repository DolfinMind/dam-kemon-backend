package com.damKemon.dam.kemon.service;

import com.damKemon.dam.kemon.model.PendingShop;
import com.damKemon.dam.kemon.model.Shop;
import com.damKemon.dam.kemon.repository.PendingShopRepository;
import com.damKemon.dam.kemon.repository.ShopRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Promotes a {@link PendingShop} (queued by discovery or shop submissions) into
 * an active {@link Shop} the indexer will crawl.
 *
 * <p>Mirrors the manual {@code POST /api/admin/pending-shops/{id}/approve}
 * endpoint so the indexer's catch-up auto-approve produces identical shops.
 * Kept deliberately self-contained (no controller dependency) to keep its blast
 * radius small.
 */
@Service
public class ShopApprovalService {

    private static final Logger log = LoggerFactory.getLogger(ShopApprovalService.class);

    private final ShopRepository shopRepository;
    private final PendingShopRepository pendingRepo;

    public ShopApprovalService(ShopRepository shopRepository, PendingShopRepository pendingRepo) {
        this.shopRepository = shopRepository;
        this.pendingRepo = pendingRepo;
    }

    public enum Result { APPROVED, ALREADY_EXISTS, INVALID }

    /**
     * Promote one pending shop to an active shop. Idempotent: if a shop with the
     * derived slug already exists, no duplicate is created and the pending row is
     * still marked approved (so the catch-up loop doesn't reprocess it forever).
     */
    public Result approve(PendingShop p) {
        if (p == null) return Result.INVALID;
        String basis = (p.getName() == null || p.getName().isBlank()) ? p.getBaseUrl() : p.getName();
        String slug = slugify(basis);
        if (slug.isBlank()) return Result.INVALID;

        if (shopRepository.findBySlug(slug).isPresent()) {
            markApproved(p);
            return Result.ALREADY_EXISTS;
        }
        Shop s = Shop.builder()
                .slug(slug)
                .name(p.getName())
                .baseUrl(p.getBaseUrl())
                .sitemapUrl(p.getSitemapUrl())
                .platform(p.getPlatform())
                .categories(p.getCategories() == null ? new ArrayList<>() : p.getCategories())
                .status("active")
                .health("active")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        shopRepository.save(s);
        markApproved(p);
        return Result.APPROVED;
    }

    /**
     * Approve up to {@code max} shops currently in {@code pending} status.
     * Returns the number of <em>new</em> active shops created (existing-slug
     * collisions don't count). Never throws — logs and skips on per-shop errors.
     */
    public int approvePending(int max) {
        if (max <= 0) return 0;
        List<PendingShop> pend;
        try {
            pend = pendingRepo.findByStatus("pending");
        } catch (Exception e) {
            log.warn("Auto-approve: could not list pending shops: {}", e.getMessage());
            return 0;
        }
        int created = 0;
        for (PendingShop p : pend) {
            if (created >= max) break;
            try {
                if (approve(p) == Result.APPROVED) created++;
            } catch (Exception e) {
                log.warn("Auto-approve: failed on pending {}: {}",
                        p == null ? "?" : p.getId(), e.getMessage());
            }
        }
        if (created > 0) log.info("Auto-approve: promoted {} pending shop(s) to active", created);
        return created;
    }

    private void markApproved(PendingShop p) {
        try {
            p.setStatus("approved");
            p.setReviewedAt(LocalDateTime.now());
            pendingRepo.save(p);
        } catch (Exception e) {
            log.debug("Auto-approve: could not mark pending {} approved: {}", p.getId(), e.getMessage());
        }
    }

    /** Same slug rules the admin approve endpoint uses. */
    public static String slugify(String name) {
        if (name == null || name.isBlank()) return "";
        return name.toLowerCase().replaceAll("[^a-z0-9\\s-]", "")
                .replaceAll("\\s+", "-").replaceAll("-+", "-")
                .replaceAll("^-|-$", "");
    }
}
