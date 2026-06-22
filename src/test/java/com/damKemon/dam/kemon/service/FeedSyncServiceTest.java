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
    void numStripsCurrencyAndCommas() {
        assertEquals(1234.5, FeedSyncService.num("৳ 1,234.50 BDT"));
        assertNull(FeedSyncService.num("call for price"));
    }
}
