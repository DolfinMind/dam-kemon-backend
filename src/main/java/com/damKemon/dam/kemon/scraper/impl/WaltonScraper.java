package com.damKemon.dam.kemon.scraper.impl;

import com.damKemon.dam.kemon.intelligence.PriceParser;
import com.damKemon.dam.kemon.scraper.BaseScraper;
import com.damKemon.dam.kemon.scraper.GenericProductExtractor;
import com.damKemon.dam.kemon.scraper.ProductExtractor;
import com.damKemon.dam.kemon.scraper.ScrapedProduct;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

/**
 * Walton — Bangladesh's largest domestic electronics + appliance brand
 * (waltonbd.com). WordPress + WooCommerce under the hood but with a
 * heavily customised theme; the default {@link WooCommerceExtractor}
 * selectors miss Walton's price block because they use bespoke class
 * names ({@code .wlt-price}, {@code .walton-product-info}).
 *
 * <p>Why dedicated rather than relying on JSON-LD: Walton's theme does
 * emit OG tags but the {@code product:price:amount} is rendered as
 * "BDT 53,500" rather than the bare number, which trips
 * {@link com.damKemon.dam.kemon.intelligence.PriceParser} when used via
 * generic OG. The site-specific path normalises that.
 */
@Component
public class WaltonScraper extends BaseScraper implements ProductExtractor {

    private final GenericProductExtractor generic;

    public WaltonScraper(GenericProductExtractor generic) {
        this.generic = generic;
    }

    @Override public String getSiteName() { return "Walton"; }
    @Override public String getSiteSlug() { return "waltonbd"; }

    @Override
    public boolean supports(String url) {
        return url != null && url.contains("waltonbd.com");
    }

    @Override
    public ScrapedProduct extract(String url) {
        return extract(url, false);
    }

    @Override
    public ScrapedProduct extract(String url, boolean useJs) {
        Document doc;
        try { doc = fetch(url); }
        catch (Exception e) {
            log.debug("Walton fetch failed for {}: {}", url, e.getMessage());
            return null;
        }
        if (doc == null) return null;

        // 1. JSON-LD first — when the theme bothers to emit it, it's clean.
        ScrapedProduct fromLd = generic.parseJsonLd(doc);
        if (GenericProductExtractor.isValid(fromLd)) { fromLd.setProductUrl(url); return fromLd; }

        // 2. Walton custom + the standard WooCommerce fallbacks (heavily
        //    customised, but inherits the WC product wrapper).
        ScrapedProduct sp = parseWaltonSpecific(doc);
        if (GenericProductExtractor.isValid(sp)) { sp.setProductUrl(url); return sp; }

        // 3. Open Graph last — many Walton pages have og:title + a noisy
        //    "BDT 50,000" price string we still try to parse.
        ScrapedProduct fromOg = generic.parseOpenGraph(doc);
        if (GenericProductExtractor.isValid(fromOg)) { fromOg.setProductUrl(url); return fromOg; }

        return null;
    }

    private ScrapedProduct parseWaltonSpecific(Document doc) {
        Element title = doc.selectFirst(
                "h1.product_title, h1.wlt-product-title, "
              + ".walton-product-info h1, .product-detail h1, h1.entry-title, h1");
        if (title == null) return null;

        // Walton lists "Regular Price" + "Special Price" on a horizontal
        // strip. Special wins when present.
        Element priceEl = doc.selectFirst(
                ".wlt-special-price, .special-price .price, "
              + ".product-price .special, .walton-price .current, "
              + ".wlt-product-price .now, .product_meta .price ins .amount, "
              + ".summary p.price ins .amount, .summary p.price .amount, "
              + ".price .amount, span.price, .product-price");
        if (priceEl == null) return null;
        Double price = PriceParser.parseFirst(priceEl.text());
        if (price == null) return null;

        Element wasEl = doc.selectFirst(
                ".wlt-regular-price, .regular-price .price, "
              + ".product-price .regular, .walton-price .was, "
              + ".product_meta .price del .amount, "
              + ".summary p.price del .amount, .price del .amount");
        Double original = wasEl == null ? null : PriceParser.parseFirst(wasEl.text());

        Element img = doc.selectFirst(
                ".wlt-product-image img, .walton-product-image img, "
              + ".product-image img, .product-gallery img, "
              + ".woocommerce-product-gallery__image img, "
              + "img.wp-post-image, .product-photo img");
        String imgUrl = img == null ? null : img.attr("abs:src");
        if ((imgUrl == null || imgUrl.isBlank()) && img != null
                && !img.attr("abs:data-src").isBlank()) {
            imgUrl = img.attr("abs:data-src");
        }

        Element stockEl = doc.selectFirst(".stock, .availability, .wlt-stock");
        boolean inStock = stockEl == null
                || !stockEl.text().toLowerCase().contains("out of stock");

        return ScrapedProduct.builder()
                .name(title.text().trim())
                .price(price)
                .originalPrice(original)
                .imageUrl(imgUrl)
                .inStock(inStock)
                .build();
    }
}
