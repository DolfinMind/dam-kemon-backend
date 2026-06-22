package com.damKemon.dam.kemon.service;

import com.damKemon.dam.kemon.config.AppRole;
import com.damKemon.dam.kemon.indexer.BulkIndexer;
import com.damKemon.dam.kemon.model.Shop;
import com.damKemon.dam.kemon.repository.ShopRepository;
import com.damKemon.dam.kemon.scraper.ScrapedProduct;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class FeedSyncServiceTest {

    private FeedSyncService svc() {
        return new FeedSyncService(mock(ShopRepository.class), mock(BulkIndexer.class), new AppRole("web"));
    }

    @Test
    void parsesShopifyProductsJsonWithUrlAndPrice() {
        String json = """
            {"products":[
              {"title":"Phone X","handle":"phone-x","image":{"src":"http://img/x.jpg"},
               "variants":[{"price":"19,999.00","available":true}]},
              {"title":"No price","handle":"np","variants":[]}
            ]}""";
        Shop shop = Shop.builder().baseUrl("https://store.example/").build();
        List<ScrapedProduct> out = svc().parseShopify(json, shop);

        assertEquals(1, out.size(), "item without a price is skipped");
        ScrapedProduct p = out.get(0);
        assertEquals("Phone X", p.getName());
        assertEquals(19999.0, p.getPrice());
        assertEquals("https://store.example/products/phone-x", p.getProductUrl());
        assertTrue(p.getInStock());
    }

    @Test
    void parsesGoogleMerchantXmlWithGNamespace() {
        String xml = """
            <?xml version="1.0"?>
            <rss xmlns:g="http://base.google.com/ns/1.0"><channel>
              <item><g:title>Laptop Y</g:title><link>https://s/y</link>
                <g:price>54999 BDT</g:price><g:availability>in stock</g:availability></item>
              <item><title>No price item</title><link>https://s/z</link></item>
            </channel></rss>""";
        List<ScrapedProduct> out = svc().parseXml(xml);

        assertEquals(1, out.size(), "item without a price is skipped");
        assertEquals("Laptop Y", out.get(0).getName());
        assertEquals(54999.0, out.get(0).getPrice());
        assertEquals("https://s/y", out.get(0).getProductUrl());
    }

    @Test
    void parsesWooCommerceStoreApiAndDividesMinorUnits() {
        String json = """
            [{"name":"Phone Mount","permalink":"https://s/p/mount",
              "prices":{"price":"199000","currency_minor_unit":2},
              "images":[{"src":"http://img/m.jpg"}],"is_in_stock":true},
             {"name":"No price item","prices":null}]""";
        Shop shop = Shop.builder().baseUrl("https://s/").build();
        List<ScrapedProduct> out = svc().parseWoo(json, shop);

        assertEquals(1, out.size(), "item without prices is skipped");
        ScrapedProduct p = out.get(0);
        assertEquals("Phone Mount", p.getName());
        assertEquals(1990.0, p.getPrice(), "199000 minor units / 10^2 = 1990.00");
        assertEquals("https://s/p/mount", p.getProductUrl());
        assertTrue(p.getInStock());
    }

    @Test
    void parseRoutesWooArrayToWooParser() {
        // bare array carrying a prices object → must NOT go through the Shopify path
        String json = "[{\"name\":\"X\",\"permalink\":\"https://s/x\",\"prices\":{\"price\":\"5000\",\"currency_minor_unit\":2}}]";
        List<ScrapedProduct> out = svc().parse(json, Shop.builder().baseUrl("https://s/").build());
        assertEquals(1, out.size());
        assertEquals(50.0, out.get(0).getPrice());
    }

    @Test
    void numStripsCurrencyAndCommas() {
        assertEquals(1234.5, FeedSyncService.num("৳ 1,234.50 BDT"));
        assertNull(FeedSyncService.num("call for price"));
    }
}
