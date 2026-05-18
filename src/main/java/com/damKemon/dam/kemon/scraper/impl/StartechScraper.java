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
 * Startech product-page extractor. Startech is fully server-rendered with
 * stable, simple selectors — the per-site path is the primary; schema.org /
 * OG are tried only if the selectors miss.
 */
@Component
public class StartechScraper extends BaseScraper implements ProductExtractor {

    private final GenericProductExtractor generic;

    public StartechScraper(GenericProductExtractor generic) {
        this.generic = generic;
    }

    @Override public String getSiteName() { return "Startech"; }
    @Override public String getSiteSlug() { return "startech"; }

    @Override
    public boolean supports(String url) {
        return url != null && url.contains("startech.com.bd");
    }

    @Override
    public ScrapedProduct extract(String url) {
        Document doc;
        try {
            doc = fetch(url);
        } catch (Exception e) {
            log.debug("Startech fetch failed for {}: {}", url, e.getMessage());
            return null;
        }

        ScrapedProduct sp = parseStartechSpecific(doc);
        if (GenericProductExtractor.isValid(sp)) { sp.setProductUrl(url); return sp; }

        ScrapedProduct fromLd = generic.parseJsonLd(doc);
        if (GenericProductExtractor.isValid(fromLd)) { fromLd.setProductUrl(url); return fromLd; }
        ScrapedProduct fromOg = generic.parseOpenGraph(doc);
        if (GenericProductExtractor.isValid(fromOg)) { fromOg.setProductUrl(url); return fromOg; }
        return null;
    }

    private ScrapedProduct parseStartechSpecific(Document doc) {
        Element title = doc.selectFirst("h1.product-name, .product-name h1, h1");
        Element priceTd = doc.selectFirst("td.product-info-data.product-price, .product-price-update, .product-price");
        if (title == null || priceTd == null) return null;
        Double parsed = PriceParser.parseFirst(priceTd.text());
        if (parsed == null) return null;
        Element img = doc.selectFirst(".product-img-holder img, .product-image img, img.product-image-loader");
        return ScrapedProduct.builder()
                .name(title.text().trim()).price(parsed)
                .imageUrl(img == null ? null : img.attr("abs:src"))
                .inStock(true).build();
    }
}
