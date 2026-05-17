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
 * Walton — Bangladesh's largest local electronics brand. Site is React-rendered
 * so we prefer Playwright; jsoup is fallback.
 */
@Component
public class WaltonScraper extends BaseScraper {

    private static final String BASE_URL = "https://waltonbd.com";

    private final BrowserFetcher browserFetcher;

    public WaltonScraper(BrowserFetcher browserFetcher) {
        this.browserFetcher = browserFetcher;
    }

    @Override public String getSiteName() { return "Walton"; }
    @Override public String getSiteSlug() { return "walton"; }
    @Override public String getBaseUrl()  { return BASE_URL; }

    @Override
    public Set<ProductCategory> getSupportedCategories() {
        return EnumSet.of(
                ProductCategory.AC, ProductCategory.REFRIGERATOR, ProductCategory.TV,
                ProductCategory.APPLIANCE, ProductCategory.SMARTPHONE, ProductCategory.LAPTOP);
    }

    @Override
    public List<ScrapedProduct> search(String query) {
        String url = BASE_URL + "/search?q=" + encode(query);
        Document doc = null;

        if (browserFetcher.isAvailable()) {
            doc = browserFetcher.fetchDocument(url);
        }
        if (doc == null) {
            try {
                doc = fetch(url);
                log.debug("Walton: using jsoup fallback");
            } catch (Exception e) {
                log.warn("Walton fetch failed: {}", e.getMessage());
                return new ArrayList<>();
            }
        }

        List<ScrapedProduct> products = new ArrayList<>();
        Elements cards = doc.select(".product-card, .product-item, .product-list-item, .product, [class*='product-grid'] > div");

        for (Element card : cards) {
            try {
                Element nameEl = card.selectFirst(".product-title a, .product-name a, h3 a, h4 a, a[title]");
                Element priceEl = card.selectFirst(".product-price, .price, .price-amount, span[class*='price']");
                if (nameEl == null || priceEl == null) continue;

                String name = nameEl.attr("title");
                if (name.isBlank()) name = nameEl.text().trim();
                if (name.isBlank()) continue;

                Double price = PriceParser.parseFirst(priceEl.text());
                if (price == null) continue;

                Element oldEl = card.selectFirst(".old-price, .regular-price, del");
                Double original = oldEl == null ? null : PriceParser.parseFirst(oldEl.text());

                Element imgEl = card.selectFirst("img");
                String img = imgEl == null ? null : imgEl.attr("abs:src");
                String productUrl = nameEl.attr("abs:href");

                products.add(ScrapedProduct.builder()
                        .name(name).price(price).originalPrice(original)
                        .productUrl(productUrl).imageUrl(img).inStock(true).build());
            } catch (Exception e) {
                log.debug("Walton card parse error: {}", e.getMessage());
            }
        }
        log.info("Walton returned {} products for '{}'", products.size(), query);
        return products;
    }
}
