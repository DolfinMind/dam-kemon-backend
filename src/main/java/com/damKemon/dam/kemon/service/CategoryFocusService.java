package com.damKemon.dam.kemon.service;

import com.damKemon.dam.kemon.intelligence.ProductCategory;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

/**
 * Single source of truth for the catalog's category focus. Damkemon is scoped to
 * computing + mobile only — smartphones, laptops, desktops/workstations (incl.
 * components & peripherals) and accessories — so the catalog stays a tight
 * price-comparison engine with high cross-shop overlap instead of a diluted
 * everything-store.
 *
 * <p>Used in two places:
 * <ul>
 *   <li>the indexer gates new products at persist time (out-of-scope items are
 *       never written), and</li>
 *   <li>{@code ProductService.focusCleanup} re-classifies + purges existing rows
 *       down to the allowed set.</li>
 * </ul>
 */
@Service
public class CategoryFocusService {

    private static final Logger log = LoggerFactory.getLogger(CategoryFocusService.class);

    @Value("${category-focus.enabled:true}")
    private boolean enabled;

    /** Enum names of the allowed categories. Default = the agreed computing+mobile set
     *  (HEADPHONE included: earbuds/headphones/speakers count as mobile accessories). */
    @Value("${category-focus.allowed:SMARTPHONE,LAPTOP,DESKTOP,ACCESSORY,MONITOR,STORAGE,NETWORKING,PRINTER,HEADPHONE}")
    private String allowedCsv;

    private Set<ProductCategory> allowed = EnumSet.noneOf(ProductCategory.class);
    /** Lower-cased labels of {@link #allowed}, e.g. "desktops & pc" — matches Product.category. */
    private Set<String> allowedLabels = new HashSet<>();

    @PostConstruct
    void init() {
        Set<ProductCategory> set = EnumSet.noneOf(ProductCategory.class);
        for (String raw : allowedCsv.split(",")) {
            String name = raw.trim().toUpperCase();
            if (name.isEmpty()) continue;
            try { set.add(ProductCategory.valueOf(name)); }
            catch (IllegalArgumentException e) { log.warn("CategoryFocus: unknown category '{}' ignored", raw); }
        }
        this.allowed = set;
        Set<String> labels = new HashSet<>();
        for (ProductCategory c : set) labels.add(c.getLabel().toLowerCase());
        this.allowedLabels = labels;
        log.info("CategoryFocus: {} — allowed categories: {}",
                enabled ? "ENABLED" : "disabled (no gating/cleanup)", labels);
    }

    public boolean isEnabled() { return enabled; }

    public Set<ProductCategory> allowed() { return allowed; }

    public boolean isAllowed(ProductCategory c) { return c != null && allowed.contains(c); }

    /** Case-insensitive check against a stored {@code Product.category} label. */
    public boolean isAllowedLabel(String storedCategory) {
        return storedCategory != null && allowedLabels.contains(storedCategory.trim().toLowerCase());
    }
}
