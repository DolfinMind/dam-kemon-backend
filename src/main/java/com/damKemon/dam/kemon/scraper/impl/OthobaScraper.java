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

/** Othoba — Pran-RFL's general marketplace, strong on appliances, fashion, groceries. */
@Component
public class OthobaScraper extends BaseScraper {

    private static final String BASE_URL = "https://www.othoba.com";

    @Override public String getSiteName() { return "Othoba"; }
    @Override public String getSiteSlug() { return "othoba"; }
    @Override public String getBaseUrl()  { return BASE_URL; }

    @Override
    public boolean handlesGeneralQueries() { return true; }

    @Override
    public Set<ProductCategory> getSupportedCategories() {
        return EnumSet.of(
                ProductCategory.SMARTPHONE, ProductCategory.LAPTOP, ProductCategory.HEADPHONE,
                ProductCategory.SMARTWATCH, ProductCategory.TV, ProductCategory.APPLIANCE,
                ProductCategory.AC, ProductCategory.REFRIGERATOR, ProductCategory.KITCHEN,
                ProductCategory.FASHION, ProductCategory.BEAUTY, ProductCategory.GROCERY,
                ProductCategory.BABY, ProductCategory.FURNITURE);
    }

    @Override
    public List<ScrapedProduct> search(String query) {
        List<ScrapedProduct> products = new ArrayList<>();
        try {
            Document doc = fetch(BASE_URL + "/search?keyword=" + encode(query));

            Elements cards = doc.select(".product-item, .product-card, .product, div[class*='product-grid']");
            for (Element card : cards) {
                try {
                    Element nameEl = card.selectFirst(".product-title a, .product-name a, h3 a, h4 a, a[title]");
                    Element priceEl = card.selectFirst(".special-price, .product-price, .price, span[class*='price']");
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
                    String url = nameEl.attr("abs:href");

                    products.add(ScrapedProduct.builder()
                            .name(name).price(price).originalPrice(original)
                            .productUrl(url).imageUrl(img).inStock(true).build());
                } catch (Exception e) {
                    log.debug("Othoba card parse error: {}", e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("Othoba search failed: {}", e.getMessage());
        }
        return products;
    }
}
