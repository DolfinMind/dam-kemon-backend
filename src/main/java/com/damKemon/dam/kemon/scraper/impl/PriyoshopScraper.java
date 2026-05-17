package com.damKemon.dam.kemon.scraper.impl;

import com.damKemon.dam.kemon.intelligence.PriceParser;
import com.damKemon.dam.kemon.intelligence.ProductCategory;
import com.damKemon.dam.kemon.scraper.BaseScraper;
import com.damKemon.dam.kemon.scraper.ScrapedProduct;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** Priyoshop — general marketplace, especially strong on fashion, beauty, lifestyle. */
@Component
public class PriyoshopScraper extends BaseScraper {

    private static final String BASE_URL = "https://www.priyoshop.com";

    @Override public String getSiteName() { return "Priyoshop"; }
    @Override public String getSiteSlug() { return "priyoshop"; }
    @Override public String getBaseUrl()  { return BASE_URL; }

    @Override
    public boolean handlesGeneralQueries() { return true; }

    @Override
    public Set<ProductCategory> getSupportedCategories() {
        return EnumSet.of(
                ProductCategory.FASHION, ProductCategory.BEAUTY, ProductCategory.SMARTPHONE,
                ProductCategory.HEADPHONE, ProductCategory.SMARTWATCH, ProductCategory.KITCHEN,
                ProductCategory.APPLIANCE, ProductCategory.BABY, ProductCategory.SPORTS);
    }

    @Override
    public List<ScrapedProduct> search(String query) {
        List<ScrapedProduct> products = new ArrayList<>();
        try {
            Document doc = fetch(BASE_URL + "/search?q=" + encode(query));

            Elements cards = doc.select(".product-card, .product-item, [class*='product-grid'] > div");
            for (Element card : cards) {
                try {
                    Element nameEl = card.selectFirst(".product-title a, .product-name a, h3 a, a[title]");
                    Element priceEl = card.selectFirst(".price, .product-price, span[class*='price']");
                    if (nameEl == null || priceEl == null) continue;

                    String name = nameEl.attr("title");
                    if (name.isBlank()) name = nameEl.text().trim();
                    if (name.isBlank()) continue;

                    Double price = PriceParser.parseFirst(priceEl.text());
                    if (price == null) continue;

                    Element oldEl = card.selectFirst(".old-price, del, s, .original-price");
                    Double original = oldEl == null ? null : PriceParser.parseFirst(oldEl.text());

                    Element imgEl = card.selectFirst("img");
                    String img = imgEl == null ? null : imgEl.attr("abs:src");
                    String url = nameEl.attr("abs:href");

                    products.add(ScrapedProduct.builder()
                            .name(name).price(price).originalPrice(original)
                            .productUrl(url).imageUrl(img).inStock(true).build());
                } catch (Exception e) {
                    log.debug("Priyoshop card parse error: {}", e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("Priyoshop search failed: {}", e.getMessage());
        }
        return products;
    }
}
