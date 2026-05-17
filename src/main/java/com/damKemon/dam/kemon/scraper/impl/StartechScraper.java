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
public class StartechScraper extends BaseScraper {

    private static final String BASE_URL = "https://www.startech.com.bd";

    @Override public String getSiteName() { return "Startech"; }
    @Override public String getSiteSlug() { return "startech"; }
    @Override public String getBaseUrl()  { return BASE_URL; }

    @Override
    public Set<ProductCategory> getSupportedCategories() {
        return EnumSet.of(
                ProductCategory.LAPTOP, ProductCategory.DESKTOP, ProductCategory.SMARTPHONE,
                ProductCategory.TABLET, ProductCategory.HEADPHONE, ProductCategory.CAMERA,
                ProductCategory.GAMING, ProductCategory.SMARTWATCH, ProductCategory.TV,
                ProductCategory.APPLIANCE);
    }

    @Override
    public List<ScrapedProduct> search(String query) {
        List<ScrapedProduct> products = new ArrayList<>();
        try {
            Document doc = fetch(BASE_URL + "/product/search?search=" + encode(query));

            Elements cards = doc.select(".p-item");
            for (Element card : cards) {
                try {
                    Element nameEl  = card.selectFirst(".p-item-name a");
                    Element priceEl = card.selectFirst(".p-item-price span, .p-item-price");
                    if (nameEl == null) continue;

                    String name = nameEl.text().trim();
                    Double price = priceEl == null ? null : PriceParser.parseFirst(priceEl.text());
                    if (name.isBlank() || price == null) continue;

                    Element imgEl = card.selectFirst(".p-item-img img");
                    String img = imgEl == null ? null : imgEl.attr("src");
                    String url = nameEl.attr("abs:href");

                    boolean inStock = card.selectFirst(".out-of-stock, .stock-out") == null;

                    products.add(ScrapedProduct.builder()
                            .name(name).price(price).productUrl(url).imageUrl(img).inStock(inStock).build());
                } catch (Exception e) {
                    log.debug("Startech card parse error: {}", e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("Startech search failed: {}", e.getMessage());
        }
        return products;
    }
}
