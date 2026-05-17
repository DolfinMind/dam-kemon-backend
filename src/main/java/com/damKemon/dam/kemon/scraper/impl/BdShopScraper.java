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

/** BD-Shop — general electronics & appliances + accessories. */
@Component
public class BdShopScraper extends BaseScraper {

    private static final String BASE_URL = "https://www.bdshop.com";

    @Override public String getSiteName() { return "BD-Shop"; }
    @Override public String getSiteSlug() { return "bdshop"; }
    @Override public String getBaseUrl()  { return BASE_URL; }

    @Override
    public boolean handlesGeneralQueries() { return true; }

    @Override
    public Set<ProductCategory> getSupportedCategories() {
        return EnumSet.of(
                ProductCategory.SMARTPHONE, ProductCategory.LAPTOP, ProductCategory.TABLET,
                ProductCategory.HEADPHONE, ProductCategory.CAMERA, ProductCategory.SMARTWATCH,
                ProductCategory.TV, ProductCategory.APPLIANCE, ProductCategory.AC,
                ProductCategory.REFRIGERATOR, ProductCategory.GAMING, ProductCategory.BEAUTY,
                ProductCategory.SPORTS);
    }

    @Override
    public List<ScrapedProduct> search(String query) {
        List<ScrapedProduct> products = new ArrayList<>();
        try {
            Document doc = fetch(BASE_URL + "/index.php?route=product/search&search=" + encode(query));

            Elements cards = doc.select(".product-thumb, .product-layout, .product-grid");
            for (Element card : cards) {
                try {
                    Element nameEl = card.selectFirst(".name a, h4 a, .caption a, a.product-name");
                    Element priceEl = card.selectFirst(".price-new, .price, span[class*='price']");
                    if (nameEl == null || priceEl == null) continue;

                    String name = nameEl.text().trim();
                    Double price = PriceParser.parseFirst(priceEl.text());
                    if (name.isBlank() || price == null) continue;

                    Element oldEl = card.selectFirst(".price-old, .old-price, del, s");
                    Double original = oldEl == null ? null : PriceParser.parseFirst(oldEl.text());

                    Element imgEl = card.selectFirst("img");
                    String img = imgEl == null ? null : imgEl.attr("abs:src");
                    String url = nameEl.attr("abs:href");

                    products.add(ScrapedProduct.builder()
                            .name(name).price(price).originalPrice(original)
                            .productUrl(url).imageUrl(img).inStock(true).build());
                } catch (Exception e) {
                    log.debug("BD-Shop card parse error: {}", e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("BD-Shop search failed: {}", e.getMessage());
        }
        return products;
    }
}
