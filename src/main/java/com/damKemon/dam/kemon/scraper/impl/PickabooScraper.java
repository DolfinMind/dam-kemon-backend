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
public class PickabooScraper extends BaseScraper {

    private static final String BASE_URL = "https://www.pickaboo.com";

    @Override public String getSiteName() { return "Pickaboo"; }
    @Override public String getSiteSlug() { return "pickaboo"; }
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
                ProductCategory.FASHION);
    }

    @Override
    public List<ScrapedProduct> search(String query) {
        List<ScrapedProduct> products = new ArrayList<>();
        try {
            Document doc = fetch(BASE_URL + "/catalogsearch/result/?q=" + encode(query));

            Elements cards = doc.select(".product-item, li.product-item, .item.product.product-item");

            for (Element card : cards) {
                try {
                    Element nameEl = card.selectFirst(".product-item-link, .product-name a, a.product-item-link");
                    Element priceEl = card.selectFirst(".special-price .price, .price, span.price");
                    if (nameEl == null || priceEl == null) continue;

                    String name = nameEl.text().trim();
                    Double price = PriceParser.parseFirst(priceEl.text());
                    if (name.isBlank() || price == null) continue;

                    Element oldEl = card.selectFirst(".old-price .price");
                    Double original = oldEl == null ? null : PriceParser.parseFirst(oldEl.text());

                    Element imgEl = card.selectFirst("img.product-image-photo, .product-image img");
                    String img = imgEl == null ? null : imgEl.attr("src");
                    Element linkEl = card.selectFirst("a.product-item-link, a[href]");
                    String url = linkEl == null ? null : linkEl.attr("abs:href");

                    products.add(ScrapedProduct.builder()
                            .name(name).price(price).originalPrice(original)
                            .productUrl(url).imageUrl(img).inStock(true).build());
                } catch (Exception e) {
                    log.debug("Pickaboo card parse error: {}", e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("Pickaboo search failed: {}", e.getMessage());
        }
        return products;
    }
}
