package com.damKemon.dam.kemon.indexer;

import com.damKemon.dam.kemon.model.Shop;
import com.damKemon.dam.kemon.repository.ShopRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Boot-race shop duplicates (the "creatus" 500): the dedupe pass must keep the
 * doc with crawl history, transfer a hide set on ANY duplicate, and delete the
 * rest — so findBySlug callers and the admin hide button work again.
 */
class ShopDedupeTest {

    private static Shop shop(String id, String slug, LocalDateTime indexedAt, String status, String blockedBy) {
        Shop s = new Shop();
        s.setId(id);
        s.setSlug(slug);
        s.setLastIndexedAt(indexedAt);
        s.setStatus(status);
        s.setBlockedBy(blockedBy);
        s.setCreatedAt(LocalDateTime.of(2026, 1, 1, 0, 0));
        return s;
    }

    @Test
    void keepsCrawledDocTransfersHideDeletesLosers() {
        Shop crawled = shop("a", "creatus", LocalDateTime.of(2026, 7, 1, 3, 0), "active", null);
        Shop hiddenDupe = shop("b", "creatus", null, "blocked", "operator");
        Shop untouched = shop("c", "startech", LocalDateTime.of(2026, 7, 1, 3, 0), "active", null);

        ShopRepository repo = mock(ShopRepository.class);
        when(repo.findAll()).thenReturn(List.of(crawled, hiddenDupe, untouched));

        new ShopCatalogBootstrap(repo, null).dedupeShops();

        ArgumentCaptor<Shop> saved = ArgumentCaptor.forClass(Shop.class);
        verify(repo).save(saved.capture());
        assertEquals("a", saved.getValue().getId(), "survivor = the doc with crawl history");
        assertEquals("blocked", saved.getValue().getStatus(), "hide on a duplicate transfers to survivor");
        assertEquals("operator", saved.getValue().getBlockedBy());
        verify(repo).delete(hiddenDupe);
        verify(repo, never()).delete(crawled);
        verify(repo, never()).delete(untouched);
    }
}
