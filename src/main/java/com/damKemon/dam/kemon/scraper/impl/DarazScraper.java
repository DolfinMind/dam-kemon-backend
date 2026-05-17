package com.damKemon.dam.kemon.scraper.impl;

import com.damKemon.dam.kemon.intelligence.PriceParser;
import com.damKemon.dam.kemon.intelligence.ProductCategory;
import com.damKemon.dam.kemon.scraper.BaseScraper;
import com.damKemon.dam.kemon.scraper.BrowserFetcher;
import com.damKemon.dam.kemon.scraper.ScrapedProduct;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Daraz uses heavy client-side rendering — jsoup gets a near-empty shell.
 * This scraper prefers Playwright when available, falls back to jsoup
 * (which will usually return 0 results but won't crash).
 */
@Component
public class DarazScraper extends BaseScraper {

    private static final String BASE_URL = "https://www.daraz.com.bd";

    private final BrowserFetcher browserFetcher;

    public DarazScraper(BrowserFetcher browserFetcher) {
        this.browserFetcher = browserFetcher;
    }

    @Override public String getSiteName() { return "Daraz"; }
    @Override public String getSiteSlug() { return "daraz"; }
    @Override public String getBaseUrl()  { return BASE_URL; }

    @Override public boolean handlesGeneralQueries() { return true; }

    @Override
    public Set<ProductCategory> getSupportedCategories() {
        return EnumSet.allOf(ProductCategory.class);
    }

    @Override
    public List<ScrapedProduct> search(String query) {
        String url = BASE_URL + "/catalog/?q=" + encode(query);
        Document doc = null;

        if (browserFetcher.isAvailable()) {
            doc = browserFetcher.fetchDocument(url);
        }
        if (doc == null) {
            try {
                doc = fetch(url);
                log.debug("Daraz: using jsoup fallback (Playwright unavailable)");
            } catch (Exception e) {
                log.warn("Daraz fetch failed: {}", e.getMessage());
                return new ArrayList<>();
            }
        }

        List<ScrapedProduct> products = new ArrayList<>();
        Elements cards = doc.select("[data-qa-locator='product-item']");
        if (cards.isEmpty()) cards = doc.select(".gridItem--Yd0sa, div[data-tracking='product-card']");
        if (cards.isEmpty()) cards = doc.select("div[data-qa-locator^='product']");

        for (Element card : cards) {
            try {
                Element nameEl  = card.selectFirst("a[title]");
                Element priceEl = card.selectFirst("span.currency--GVKjl, span[class*='currency'], span[class*='price']");

                if (nameEl == null) continue;
                String name = nameEl.attr("title");
                if (name.isBlank()) name = nameEl.text().trim();
                if (name.isBlank()) continue;

                Double price = priceEl == null ? null : PriceParser.parseFirst(priceEl.text());
                if (price == null) continue;

                Element imgEl = card.selectFirst("img");
                Element linkEl = card.selectFirst("a[href]");
                String img = imgEl == null ? null : imgEl.attr("src");
                String productUrl = linkEl == null ? null : linkEl.attr("abs:href");

                products.add(ScrapedProduct.builder()
                        .name(name).price(price)
                        .productUrl(productUrl).imageUrl(img).inStock(true).build());
            } catch (Exception e) {
                log.debug("Daraz card parse error: {}", e.getMessage());
            }
        }
        log.info("Daraz returned {} products for '{}'", products.size(), query);
        return products;
    }
}
