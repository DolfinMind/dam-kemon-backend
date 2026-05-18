package com.damKemon.dam.kemon.scraper.impl;

import com.damKemon.dam.kemon.intelligence.PriceParser;
import com.damKemon.dam.kemon.scraper.BaseScraper;
import com.damKemon.dam.kemon.scraper.BrowserFetcher;
import com.damKemon.dam.kemon.scraper.GenericProductExtractor;
import com.damKemon.dam.kemon.scraper.ProductExtractor;
import com.damKemon.dam.kemon.scraper.ScrapedProduct;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

/**
 * Daraz product-page extractor.
 *
 * <p>Daraz uses heavy CSR — without Playwright, jsoup gets the static shell
 * which still contains the JSON-LD Product block (Daraz writes it inline for
 * SEO). We try CSS selectors first, then fall back to the generic
 * schema.org / Open Graph extraction on the same document.
 */
@Component
public class DarazScraper extends BaseScraper implements ProductExtractor {

    private final BrowserFetcher browser;
    private final GenericProductExtractor generic;

    public DarazScraper(BrowserFetcher browser, GenericProductExtractor generic) {
        this.browser = browser;
        this.generic = generic;
    }

    @Override public String getSiteName() { return "Daraz"; }
    @Override public String getSiteSlug() { return "daraz"; }

    @Override
    public boolean supports(String url) {
        return url != null && url.contains("daraz.com.bd");
    }

    @Override
    public ScrapedProduct extract(String url) {
        Document doc = fetchDoc(url);
        if (doc == null) return null;

        ScrapedProduct sp = parseDarazCard(doc);
        if (GenericProductExtractor.isValid(sp)) {
            sp.setProductUrl(url);
            return sp;
        }

        // Fallback to schema.org JSON-LD or OpenGraph on the same document
        ScrapedProduct fromLd = generic.parseJsonLd(doc);
        if (GenericProductExtractor.isValid(fromLd)) { fromLd.setProductUrl(url); return fromLd; }
        ScrapedProduct fromOg = generic.parseOpenGraph(doc);
        if (GenericProductExtractor.isValid(fromOg)) { fromOg.setProductUrl(url); return fromOg; }
        return null;
    }

    private Document fetchDoc(String url) {
        if (browser.isAvailable()) {
            Document d = browser.fetchDocument(url);
            if (d != null) return d;
        }
        try {
            return fetch(url);
        } catch (Exception e) {
            log.debug("Daraz fetch failed for {}: {}", url, e.getMessage());
            return null;
        }
    }

    private ScrapedProduct parseDarazCard(Document doc) {
        Element title = doc.selectFirst(".pdp-mod-product-badge-title, h1.pdp-product-title");
        Element price = doc.selectFirst(".pdp-price, .pdp-price_type_normal, span.pdp-price_color_orange");
        if (title == null || price == null) return null;
        Double parsedPrice = PriceParser.parseFirst(price.text());
        if (parsedPrice == null) return null;
        Element img = doc.selectFirst("img.pdp-mod-common-image, img.gallery-preview-panel__image");
        return ScrapedProduct.builder()
                .name(title.text().trim()).price(parsedPrice)
                .imageUrl(img == null ? null : img.attr("abs:src"))
                .inStock(true).build();
    }
}
