package com.damKemon.dam.kemon.scraper.impl;

import com.damKemon.dam.kemon.intelligence.PriceParser;
import com.damKemon.dam.kemon.scraper.BaseScraper;
import com.damKemon.dam.kemon.scraper.GenericProductExtractor;
import com.damKemon.dam.kemon.scraper.ProductExtractor;
import com.damKemon.dam.kemon.scraper.ScrapedProduct;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Platform-tier extractor for WooCommerce / WordPress storefronts. Built
 * because the GenericProductExtractor's JSON-LD path silently fails on
 * the (very common) WooCommerce themes that don't emit product schema —
 * we end up with thousands of crawlable product URLs and zero extractions.
 *
 * <p>Covers the WordPress + WooCommerce shops in {@code shops.json}:
 * Walton, Transcom, Best Electronics, Esquire, Rangs, MTL Smart Plaza,
 * Jamuna, Vision Empori, Apple Gadgets BD, Sumash Tech, Mobile Buzz BD,
 * Mobile Dokan, Custom Mac BD, Rio International, Gadget & Gear,
 * Binary Logic, Computer Source, Computer Village, Techland BD,
 * Skyland, Doel BD, Larrive, Richmond, Sundori, Charupath, Cosmetics BD,
 * Boi Bichitra, Pathak Shamabesh, Boi Mela, IT Tech BD, Dazzle.
 *
 * <p>Strategy:
 * <ol>
 *   <li>{@link GenericProductExtractor#parseJsonLd} (Yoast SEO + most modern
 *       themes emit this).</li>
 *   <li>Standard WooCommerce-theme selectors — Storefront, Astra, OceanWP,
 *       Flatsome and the unmodified default share most class names:
 *       {@code .product_title}, {@code .price .amount}, {@code .summary
 *       .price}, {@code .woocommerce-product-gallery img}.</li>
 *   <li>{@link GenericProductExtractor#parseOpenGraph} as a final fallback —
 *       many WooCommerce installs at least emit {@code og:title} +
 *       {@code product:price:amount}.</li>
 * </ol>
 *
 * <p>The {@link #supports} signal is a "this URL is plausibly WooCommerce"
 * heuristic — we claim it when no more-specific extractor (e.g. Startech,
 * Ryans) has already claimed the host. Detection is by the {@code /product/}
 * URL fragment which WooCommerce uses for product permalinks by default.
 * False positives are harmless — extract returns null and the generic
 * extractor gets a turn.
 */
@Component
public class WooCommerceExtractor extends BaseScraper implements ProductExtractor {

    /**
     * Hosts we never claim — these have their own dedicated extractor that
     * runs first or use a totally different platform. Cheap allowlist on
     * the negative side instead of an exhaustive positive one.
     */
    private static final List<String> NEVER_CLAIM = List.of(
            "startech.com.bd", "pickaboo.com", "daraz.com.bd", "ryans.com",
            "bdshop.com", "ajkerdeal.com", "rokomari.com", "othoba.com",
            "priyoshop.com", "chaldal.com",
            // Walton has its own extractor because its WC theme is too
            // customised for the generic selectors below to match.
            "waltonbd.com",
            // Shopify hosts — handled by ShopifyExtractor
            "aarong.com", "sailorbd.com", "yellowclothing.net", "ecstasy.com.bd",
            "anjans.com", "lereve.com.bd", "catseye.com.bd", "infinity.com.bd",
            "twelve.com.bd", "shop.shajgoj.com", "skincafebd.com"
    );

    private final GenericProductExtractor generic;

    public WooCommerceExtractor(GenericProductExtractor generic) {
        this.generic = generic;
    }

    @Override public String getSiteName() { return "WooCommerce"; }
    @Override public String getSiteSlug() { return "woocommerce"; }

    @Override
    public boolean supports(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase();
        for (String never : NEVER_CLAIM) if (lower.contains(never)) return false;
        // WooCommerce default permalink. Some themes drop /product/ via
        // permalink customisation; for those the GenericProductExtractor
        // path still handles them (most still emit OG/JSON-LD).
        return lower.contains("/product/") || lower.contains("/?p=") || lower.matches(".*/[a-z0-9-]+/?$");
    }

    @Override
    public ScrapedProduct extract(String url) {
        return extract(url, false);
    }

    @Override
    public ScrapedProduct extract(String url, boolean useJs) {
        Document doc;
        try {
            doc = fetch(url);
        } catch (Exception e) {
            log.debug("WooCommerce fetch failed for {}: {}", url, e.getMessage());
            return null;
        }
        if (doc == null) return null;

        // 1. JSON-LD — most modern themes (Yoast, RankMath) emit this.
        ScrapedProduct fromLd = generic.parseJsonLd(doc);
        if (GenericProductExtractor.isValid(fromLd)) { fromLd.setProductUrl(url); return fromLd; }

        // 2. WooCommerce-specific selectors — the universal WC theme idiom.
        ScrapedProduct fromTheme = parseWooCommerceTheme(doc);
        if (GenericProductExtractor.isValid(fromTheme)) { fromTheme.setProductUrl(url); return fromTheme; }

        // 3. Open Graph fallback.
        ScrapedProduct fromOg = generic.parseOpenGraph(doc);
        if (GenericProductExtractor.isValid(fromOg)) { fromOg.setProductUrl(url); return fromOg; }

        return null;
    }

    /**
     * WooCommerce class names. The selectors are intentionally permissive
     * and ordered most-likely-to-match first — we union across multiple
     * popular themes (Storefront, Astra, OceanWP, Flatsome, Divi WC, etc.).
     */
    private ScrapedProduct parseWooCommerceTheme(Document doc) {
        // Title — h1 with .product_title is the canonical WC class.
        Element title = doc.selectFirst(
                "h1.product_title, h1.entry-title, .product-title h1, "
              + ".summary h1.product_title, .summary .product_title, "
              + ".product .summary h1, .product_summary h1, h1.product-name");
        if (title == null) return null;

        // Current price — try .price ins .amount (sale price) before .price
        // .amount (regular). Both are inside the .summary wrapper on
        // virtually every WC theme.
        Element priceNow = doc.selectFirst(
                ".summary p.price ins .woocommerce-Price-amount, "
              + ".summary p.price ins .amount, "
              + ".entry-summary p.price ins .amount, "
              + ".summary .price ins .amount, "
              + ".summary p.price .woocommerce-Price-amount, "
              + ".summary p.price .amount, "
              + ".summary .price .amount, "
              + ".entry-summary .price .amount, "
              + ".product-price .price-current, "
              + ".price-new, .current-price, .price-now, "
              + "p.price .amount, .price .amount");
        if (priceNow == null) return null;

        Double parsed = PriceParser.parseFirst(priceNow.text());
        if (parsed == null) return null;

        // Original (struck-through) price — when present.
        Element priceWas = doc.selectFirst(
                ".summary p.price del .woocommerce-Price-amount, "
              + ".summary p.price del .amount, "
              + ".summary .price del .amount, "
              + ".price-old, .old-price, p.price del .amount");
        Double original = priceWas == null ? null : PriceParser.parseFirst(priceWas.text());

        // Image — try the WC gallery first.
        Element img = doc.selectFirst(
                ".woocommerce-product-gallery__image img, "
              + ".woocommerce-product-gallery img, "
              + ".product-images img, .product-gallery img, "
              + ".product-image img, figure.product-image img, "
              + ".summary img, img.wp-post-image");
        String imgUrl = img == null ? null : img.attr("abs:src");
        if (imgUrl == null || imgUrl.isBlank()) {
            // data-src lazy-load fallback
            if (img != null && !img.attr("abs:data-src").isBlank()) {
                imgUrl = img.attr("abs:data-src");
            }
        }

        // Stock indicator — WooCommerce uses stock class on a span.
        Element stock = doc.selectFirst(".stock, .availability");
        boolean inStock = stock == null || !stock.className().toLowerCase().contains("out-of-stock");

        return ScrapedProduct.builder()
                .name(title.text().trim())
                .price(parsed)
                .originalPrice(original)
                .imageUrl(imgUrl)
                .inStock(inStock)
                .build();
    }
}
