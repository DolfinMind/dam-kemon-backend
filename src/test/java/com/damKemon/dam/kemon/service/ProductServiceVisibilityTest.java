package com.damKemon.dam.kemon.service;

import com.damKemon.dam.kemon.config.AppRole;
import com.damKemon.dam.kemon.intelligence.QueryClassifier;
import com.damKemon.dam.kemon.model.Product;
import com.damKemon.dam.kemon.model.SitePrice;
import com.damKemon.dam.kemon.repository.AffiliateClickRepository;
import com.damKemon.dam.kemon.repository.PriceHistoryRepository;
import com.damKemon.dam.kemon.repository.ProductRepository;
import com.damKemon.dam.kemon.repository.ReviewRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProductServiceVisibilityTest {

    @Mock ProductRepository products;
    @Mock ReviewRepository reviews;
    @Mock PriceHistoryRepository history;
    @Mock TrustService trust;
    @Mock MongoTemplate mongo;
    @Mock AffiliateClickRepository clicks;
    @Mock QueryClassifier classifier;
    @Mock CategoryFocusService focus;
    @Mock ShopVisibilityService shopVisibility;
    @Mock AppRole appRole;

    private ProductService service;

    @BeforeEach
    void setUp() {
        service = new ProductService(products, reviews, history, trust, mongo, clicks,
                classifier, focus, shopVisibility, appRole);
    }

    @Test
    void legacyOutOfScopeProductCannotReachPublicDetail() {
        Product fashion = new Product();
        fashion.setId("p1");
        fashion.setName("Womens mustard ethnic wear");
        fashion.setCategory("fashion");
        fashion.setPrices(List.of(SitePrice.builder().price(5_200.0).build()));
        when(products.findById("p1")).thenReturn(Optional.of(fashion));
        when(focus.isEnabled()).thenReturn(true);
        when(focus.isAllowedLabel("fashion")).thenReturn(false);

        assertTrue(service.findByIdOrSlug("p1").isEmpty());
    }

    @Test
    void browseAllUsesOnlyFocusedCategories() {
        PageRequest page = PageRequest.of(0, 24);
        Set<String> allowed = Set.of("smartphones", "laptops");
        when(focus.isEnabled()).thenReturn(true);
        when(focus.allowedLabels()).thenReturn(allowed);
        when(products.findByCategoryIn(allowed, page)).thenReturn(Page.empty(page));

        assertTrue(service.getAllProducts(null, page).isEmpty());
        verify(products).findByCategoryIn(allowed, page);
        verify(products, never()).findAll(page);
    }
}
