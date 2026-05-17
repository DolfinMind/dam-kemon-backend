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

@Component
public class RokomariScraper extends BaseScraper {

    private static final String BASE_URL = "https://www.rokomari.com";

    @Override public String getSiteName() { return "Rokomari"; }
    @Override public String getSiteSlug() { return "rokomari"; }
    @Override public String getBaseUrl()  { return BASE_URL; }

    @Override
    public Set<ProductCategory> getSupportedCategories() {
        // Rokomari is a books platform. Only books — never appliances/electronics.
        return EnumSet.of(ProductCategory.BOOK);
    }

    @Override
    public List<ScrapedProduct> search(String query) {
        List<ScrapedProduct> products = new ArrayList<>();
        try {
            Document doc = fetch(BASE_URL + "/search?term=" + encode(query));

            Elements cards = doc.select(".book-card, .product-card, div[class*='product-card']");
            if (cards.isEmpty()) {
                cards = doc.select("a[href*='/book/']").parents();
            }

            for (Element card : cards) {
                try {
                    Element nameEl = card.selectFirst("h4 a, .book-title a, .product-title a, a[title]");
                    if (nameEl == null) continue;

                    String name = nameEl.attr("title");
                    if (name.isBlank()) name = nameEl.text().trim();
                    if (name.isBlank()) continue;

                    // *** robust price extraction — only from elements that look like price ***
                    Element priceEl = card.selectFirst("p.sell-price, span.sell-price, .price-text, .book-price, .product-price");
                    Double price = priceEl == null ? null : PriceParser.parseFirst(priceEl.text());
                    if (price == null) continue;       // no price = drop the result (no more ৳0.101 garbage)

                    Element oldEl = card.selectFirst(".old-price, .original-price, del, s");
                    Double original = oldEl == null ? null : PriceParser.parseFirst(oldEl.text());

                    Element imgEl = card.selectFirst("img");
                    String img = imgEl == null ? null : imgEl.attr("src");
                    if (img != null && img.startsWith("//")) img = "https:" + img;

                    Element linkEl = card.selectFirst("a[href]");
                    String url = linkEl == null ? null : linkEl.attr("abs:href");

                    products.add(ScrapedProduct.builder()
                            .name(name).price(price).originalPrice(original)
                            .productUrl(url).imageUrl(img).inStock(true)
                            .build());
                } catch (Exception e) {
                    log.debug("Rokomari card parse error: {}", e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("Rokomari search failed: {}", e.getMessage());
        }
        return products;
    }
}
