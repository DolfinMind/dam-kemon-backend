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
public class RyansComputersScraper extends BaseScraper {

    private static final String BASE_URL = "https://www.ryanscomputers.com";

    @Override public String getSiteName() { return "Ryans Computers"; }
    @Override public String getSiteSlug() { return "ryans"; }
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
            Document doc = fetch(BASE_URL + "/searchresult?search=" + encode(query));

            Elements cards = doc.select(".card-body.text-center, .product-item, .col-md-3.col-sm-6");

            for (Element card : cards) {
                try {
                    Element nameEl = card.selectFirst("p.card-text a, .product-title a, a.product-name");
                    Element priceEl = card.selectFirst(".pr-text, .product-price, .price");
                    if (nameEl == null || priceEl == null) continue;

                    String name = nameEl.text().trim();
                    Double price = PriceParser.parseFirst(priceEl.text());
                    if (name.isBlank() || price == null) continue;

                    Element imgEl = card.selectFirst("img");
                    String img = imgEl == null ? null : imgEl.attr("src");
                    if (img != null && !img.startsWith("http")) img = BASE_URL + img;

                    Element linkEl = card.selectFirst("a[href]");
                    String url = linkEl == null ? null : linkEl.attr("abs:href");

                    products.add(ScrapedProduct.builder()
                            .name(name).price(price).productUrl(url).imageUrl(img).inStock(true).build());
                } catch (Exception e) {
                    log.debug("Ryans card parse error: {}", e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("Ryans search failed: {}", e.getMessage());
        }
        return products;
    }
}
