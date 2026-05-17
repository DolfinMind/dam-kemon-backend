package com.damKemon.dam.kemon.scraper.impl;

import com.damKemon.dam.kemon.intelligence.PriceParser;
import com.damKemon.dam.kemon.intelligence.ProductCategory;
import com.damKemon.dam.kemon.scraper.BaseScraper;
import com.damKemon.dam.kemon.scraper.ScrapedProduct;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class ChaldalScraper extends BaseScraper {

    private static final String BASE_URL = "https://chaldal.com";
    private static final String API_URL = "https://catalog.chaldal.com/searchOld";

    @Override public String getSiteName() { return "Chaldal"; }
    @Override public String getSiteSlug() { return "chaldal"; }
    @Override public String getBaseUrl()  { return BASE_URL; }

    @Override
    public Set<ProductCategory> getSupportedCategories() {
        // Chaldal is a grocery + household platform. Never electronics, books, fashion.
        return EnumSet.of(
                ProductCategory.GROCERY,
                ProductCategory.KITCHEN,
                ProductCategory.BABY,
                ProductCategory.BEAUTY,
                ProductCategory.APPLIANCE);
    }

    @Override
    public List<ScrapedProduct> search(String query) {
        List<ScrapedProduct> products = new ArrayList<>();
        try {
            String requestBody = "{\"apiKey\":\"aeaborridge\",\"storeId\":1,\"warehouseId\":8,"
                    + "\"pageSize\":20,\"currentPageIndex\":0,\"metropolitanAreaId\":1,"
                    + "\"query\":\"" + query.replace("\"", "\\\"") + "\"}";

            String ua = userAgents == null || userAgents.isEmpty()
                    ? "Mozilla/5.0" : userAgents.get(0);
            Connection.Response response = Jsoup.connect(API_URL)
                    .userAgent(ua)
                    .timeout(timeoutMs)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .requestBody(requestBody)
                    .method(Connection.Method.POST)
                    .ignoreContentType(true)
                    .execute();

            String body = response.body();

            Pattern namePattern = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"");
            Pattern pricePattern = Pattern.compile("\"price\"\\s*:\\s*(\\d+\\.?\\d*)");
            Pattern imgPattern = Pattern.compile("\"pictureUrl\"\\s*:\\s*\"([^\"]+)\"");
            Pattern slugPattern = Pattern.compile("\"slug\"\\s*:\\s*\"([^\"]+)\"");

            String[] productBlocks = body.split("\"id\"\\s*:");
            for (int i = 1; i < productBlocks.length && i <= 20; i++) {
                try {
                    String block = productBlocks[i];
                    Matcher nm = namePattern.matcher(block);
                    Matcher pm = pricePattern.matcher(block);
                    Matcher im = imgPattern.matcher(block);
                    Matcher sm = slugPattern.matcher(block);

                    String name = nm.find() ? nm.group(1) : null;
                    if (name == null) continue;
                    Double price = pm.find() ? PriceParser.parseFirst(pm.group(1)) : null;
                    if (price == null) continue;
                    String img = im.find() ? im.group(1) : null;
                    String slug = sm.find() ? sm.group(1) : null;
                    String url = slug == null ? null : BASE_URL + "/" + slug;

                    products.add(ScrapedProduct.builder()
                            .name(name).price(price).productUrl(url).imageUrl(img).inStock(true).build());
                } catch (Exception e) {
                    log.debug("Chaldal block parse error: {}", e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("Chaldal search failed: {}", e.getMessage());
        }
        return products;
    }
}
