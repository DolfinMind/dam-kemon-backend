package com.damKemon.dam.kemon.controller;

import com.damKemon.dam.kemon.indexer.SellersBootstrap;
import com.damKemon.dam.kemon.indexer.ShopCatalogBootstrap;
import com.damKemon.dam.kemon.indexer.ShopTrustBootstrap;
import com.damKemon.dam.kemon.repository.PendingOfferRepository;
import com.damKemon.dam.kemon.repository.ProductRepository;
import com.damKemon.dam.kemon.repository.SellerRepository;
import com.damKemon.dam.kemon.repository.ShopRepository;
import org.springframework.data.repository.CrudRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Operator diagnostics for the "everything shows 0" class of bug: shows live row
 * counts for the key collections and force-runs the directory seeders on demand,
 * returning each seeder's outcome. Turns "is it empty or is the read failing?"
 * into a definite answer — and reseeds without a redeploy.
 */
@RestController
@RequestMapping("/api/admin/diag")
public class AdminDiagController {

    private final ProductRepository products;
    private final ShopRepository shops;
    private final SellerRepository sellers;
    private final PendingOfferRepository offers;
    private final ShopCatalogBootstrap shopBootstrap;
    private final SellersBootstrap sellersBootstrap;
    private final ShopTrustBootstrap trustBootstrap;

    public AdminDiagController(ProductRepository products, ShopRepository shops, SellerRepository sellers,
                              PendingOfferRepository offers, ShopCatalogBootstrap shopBootstrap,
                              SellersBootstrap sellersBootstrap, ShopTrustBootstrap trustBootstrap) {
        this.products = products;
        this.shops = shops;
        this.sellers = sellers;
        this.offers = offers;
        this.shopBootstrap = shopBootstrap;
        this.sellersBootstrap = sellersBootstrap;
        this.trustBootstrap = trustBootstrap;
    }

    @GetMapping("/collections")
    public ResponseEntity<Map<String, Object>> counts() {
        return ResponseEntity.ok(snapshot());
    }

    /** Force-run the directory seeders, then report counts + each seeder's outcome. */
    @PostMapping("/reseed")
    public ResponseEntity<Map<String, Object>> reseed() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("shopSeed", run(shopBootstrap::seed));
        out.put("sellerSeed", run(sellersBootstrap::seed));
        out.put("trustSeed", run(trustBootstrap::seed));
        out.putAll(snapshot());
        return ResponseEntity.ok(out);
    }

    private Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("products", count(products));
        out.put("shops", count(shops));
        out.put("sellers", count(sellers));
        out.put("pendingOffers", count(offers));
        return out;
    }

    private static Object count(CrudRepository<?, ?> repo) {
        try { return repo.count(); }
        catch (Exception e) { return "error: " + e.getMessage(); }
    }

    private static String run(Runnable seeder) {
        try { seeder.run(); return "ok"; }
        catch (Exception e) { return "error: " + e.getMessage(); }
    }
}
