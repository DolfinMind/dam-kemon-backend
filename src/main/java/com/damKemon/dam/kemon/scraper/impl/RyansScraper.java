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
 * Ryans Computers — one of the top-3 BD computer/electronics retailers
 * and a perpetual "0 products" entry in the indexer health view. Server-
 * rendered custom stack (Laravel by the look of it) with stable class
 * names, so per-site selectors are the primary path.
 *
 * <p>Selector strategy was reverse-engineered from the public product
 * page structure: a {@code .product_details_text} block holds the H1 and
 * the price; SKU + stock are in a sibling specs table.
 */
@Component
public class RyansScraper extends BaseScraper implements ProductExtractor {

    private final GenericProductExtractor generic;

    public RyansScraper(GenericProductExtractor generic) {
        this.generic = generic;
    }

    @Override public String getSiteName() { return "Ryans Computers"; }
    @Override public String getSiteSlug() { return "ryans"; }

    @Override
    public boolean supports(String url) {
        return url != null && url.contains("ryans.com");
    }

    @Override
    public ScrapedProduct extract(String url) {
        Document doc;
        try { doc = fetch(url); }
        catch (Exception e) {
            log.debug("Ryans fetch failed for {}: {}", url, e.getMessage());
            return null;
        }
        if (doc == null) return null;

        ScrapedProduct sp = parseRyansSpecific(doc);
        if (GenericProductExtractor.isValid(sp)) { sp.setProductUrl(url); return sp; }

        // Fallback chain — Ryans pages also emit OG and (sometimes) JSON-LD.
        ScrapedProduct fromLd = generic.parseJsonLd(doc);
        if (GenericProductExtractor.isValid(fromLd)) { fromLd.setProductUrl(url); return fromLd; }
        ScrapedProduct fromOg = generic.parseOpenGraph(doc);
        if (GenericProductExtractor.isValid(fromOg)) { fromOg.setProductUrl(url); return fromOg; }
        return null;
    }

    private ScrapedProduct parseRyansSpecific(Document doc) {
        Element title = doc.selectFirst(
                "h1.product_title_heading, .product_details_text h1, "
              + ".product_main_info h1, h1.product-title, h1");
        if (title == null) return null;

        // Ryans shows current price as a "Tk N,NNN" string inside
        // .product_main_info or .price-row.
        Element priceEl = doc.selectFirst(
                ".product_main_info .price, .product-price .current_price, "
              + ".price-row .price, .product-price, .current-price, "
              + ".special_price, .new_price");
        if (priceEl == null) return null;
        Double price = PriceParser.parseFirst(priceEl.text());
        if (price == null) return null;

        Element wasEl = doc.selectFirst(".price-row .old-price, .old-price, .regular_price, del .price");
        Double original = wasEl == null ? null : PriceParser.parseFirst(wasEl.text());

        Element img = doc.selectFirst(
                ".product-image-zoom img, .product-images img, "
              + ".product-thumb img, .swiper-slide img, .product-photo img");
        String imgUrl = img == null ? null : img.attr("abs:src");

        // Ryans uses "In Stock" / "Out of Stock" text in .product-status or
        // a CSS class on the badge.
        Element stockEl = doc.selectFirst(".product-status, .stock-status, .availability");
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
