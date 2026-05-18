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
 * Pickaboo product-page extractor. Pickaboo is mostly server-rendered with
 * good JSON-LD coverage; the per-site selectors below are mostly belt-and-
 * braces for cases where structured data is missing.
 */
@Component
public class PickabooScraper extends BaseScraper implements ProductExtractor {

    private final GenericProductExtractor generic;

    public PickabooScraper(GenericProductExtractor generic) {
        this.generic = generic;
    }

    @Override public String getSiteName() { return "Pickaboo"; }
    @Override public String getSiteSlug() { return "pickaboo"; }

    @Override
    public boolean supports(String url) {
        return url != null && url.contains("pickaboo.com");
    }

    @Override
    public ScrapedProduct extract(String url) {
        Document doc;
        try {
            doc = fetch(url);
        } catch (Exception e) {
            log.debug("Pickaboo fetch failed for {}: {}", url, e.getMessage());
            return null;
        }

        ScrapedProduct fromLd = generic.parseJsonLd(doc);
        if (GenericProductExtractor.isValid(fromLd)) { fromLd.setProductUrl(url); return fromLd; }
        ScrapedProduct fromOg = generic.parseOpenGraph(doc);
        if (GenericProductExtractor.isValid(fromOg)) { fromOg.setProductUrl(url); return fromOg; }

        ScrapedProduct sp = parsePickabooSpecific(doc);
        if (GenericProductExtractor.isValid(sp)) { sp.setProductUrl(url); return sp; }
        return null;
    }

    private ScrapedProduct parsePickabooSpecific(Document doc) {
        Element title = doc.selectFirst("h1.product-title, h1.product-name, h1");
        Element priceNow = doc.selectFirst(".product-price .new-price, .product-price-now, .price--current");
        Element priceWas = doc.selectFirst(".product-price .old-price, .product-price-old, .price--was");
        if (title == null || priceNow == null) return null;
        Double parsed = PriceParser.parseFirst(priceNow.text());
        if (parsed == null) return null;
        Double original = priceWas == null ? null : PriceParser.parseFirst(priceWas.text());
        Element img = doc.selectFirst(".product-gallery img, img.product-image, .product-image-zoom img");
        return ScrapedProduct.builder()
                .name(title.text().trim()).price(parsed).originalPrice(original)
                .imageUrl(img == null ? null : img.attr("abs:src"))
                .inStock(true).build();
    }
}
