package com.damKemon.dam.kemon.indexer;

import com.damKemon.dam.kemon.intelligence.QueryClassifier;
import com.damKemon.dam.kemon.model.Product;
import com.damKemon.dam.kemon.model.Shop;
import com.damKemon.dam.kemon.repository.IndexerRunRepository;
import com.damKemon.dam.kemon.repository.ProductRepository;
import com.damKemon.dam.kemon.repository.ShopRepository;
import com.damKemon.dam.kemon.scraper.ExtractorRegistry;
import com.damKemon.dam.kemon.scraper.ScrapedProduct;
import com.damKemon.dam.kemon.service.CategoryFocusService;
import com.damKemon.dam.kemon.service.IndexNowService;
import com.damKemon.dam.kemon.service.ShopHealthService;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BulkIndexerFastIngestTest {

    @Test
    void fastIngestUsesIndexedMatchesWithoutWarmingLsh() {
        ProductRepository products = mock(ProductRepository.class);
        Product existing = Product.builder()
                .id("p1")
                .name("Phone X")
                .prices(new ArrayList<>())
                .build();
        when(products.findAllByPriceUrl("https://shop.test/p/x"))
                .thenReturn(List.of(existing));
        when(products.save(any(Product.class))).thenAnswer(call -> call.getArgument(0));

        BulkIndexer indexer = indexer(products);
        Shop shop = Shop.builder()
                .slug("shop")
                .name("Shop")
                .baseUrl("https://shop.test")
                .build();
        ScrapedProduct offer = ScrapedProduct.builder()
                .name("Phone X")
                .price(1000.0)
                .productUrl("https://shop.test/p/x")
                .build();

        BulkIndexer.FastIngestResult result = indexer.enrichFast(shop, List.of(offer));

        assertEquals(1, result.accepted());
        assertEquals(1, result.merged());
        assertEquals(0, result.inserted());
        verify(products, never()).findAllNameViews();
    }

    @Test
    void fastIngestPropagatesMongoFailureSoCallerDoesNotAcknowledge() {
        ProductRepository products = mock(ProductRepository.class);
        when(products.findAllByPriceUrl("https://shop.test/p/x"))
                .thenThrow(new DataAccessResourceFailureException("down"));
        Shop shop = Shop.builder().slug("shop").name("Shop").build();
        ScrapedProduct offer = ScrapedProduct.builder()
                .name("Phone X")
                .price(1000.0)
                .productUrl("https://shop.test/p/x")
                .build();

        assertThrows(DataAccessResourceFailureException.class,
                () -> indexer(products).enrichFast(shop, List.of(offer)));
    }

    @Test
    void fastIngestToleratesDuplicateUrlInsteadOf500ingTheBatch() {
        // A concurrent-write race can leave two products carrying the same
        // prices.productUrl. The single-result Optional query throws
        // IncorrectResultSizeDataAccessException, which used to 500 the whole
        // batch and stall the FIFO crawler outbox behind it. The tolerant List
        // lookup must merge into one match and let the batch succeed.
        ProductRepository products = mock(ProductRepository.class);
        Product dupA = Product.builder()
                .id("a1").name("Phone X").prices(new ArrayList<>()).build();
        Product dupB = Product.builder()
                .id("b2").name("Phone X").prices(new ArrayList<>()).build();
        when(products.findAllByPriceUrl("https://shop.test/p/x"))
                .thenReturn(List.of(dupB, dupA));
        when(products.save(any(Product.class))).thenAnswer(call -> call.getArgument(0));

        BulkIndexer indexer = indexer(products);
        Shop shop = Shop.builder()
                .slug("shop").name("Shop").baseUrl("https://shop.test").build();
        ScrapedProduct offer = ScrapedProduct.builder()
                .name("Phone X")
                .price(1000.0)
                .productUrl("https://shop.test/p/x")
                .build();

        BulkIndexer.FastIngestResult result = indexer.enrichFast(shop, List.of(offer));

        assertEquals(1, result.accepted());
        assertEquals(1, result.merged());
        assertEquals(0, result.inserted());
    }

    private BulkIndexer indexer(ProductRepository products) {
        return new BulkIndexer(
                mock(ShopRepository.class),
                products,
                mock(SitemapCrawler.class),
                mock(HomepageCrawler.class),
                mock(SearchSeedCrawler.class),
                mock(ExtractorRegistry.class),
                mock(QueryClassifier.class),
                mock(ShopHealthService.class),
                mock(IndexerRunRepository.class),
                mock(ScraperLearningService.class),
                List.of(),
                mock(ApiSniffer.class),
                mock(DomCardHarvester.class),
                mock(CategoryFocusService.class),
                mock(MongoTemplate.class),
                mock(IndexNowService.class));
    }
}
