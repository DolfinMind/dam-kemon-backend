package com.damKemon.dam.kemon.service;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IndexNowServiceTest {

    @Test
    void flushDedupesBuildsUrlsAndDrainsQueue() {
        List<List<String>> sent = new ArrayList<>();
        IndexNowService svc = new IndexNowService() {
            @Override void post(List<String> urls) { sent.add(urls); }
        };
        ReflectionTestUtils.setField(svc, "enabled", true);
        ReflectionTestUtils.setField(svc, "webUrl", "https://damkemon.com/");

        svc.submit("iphone-15");
        svc.submit("iphone-15"); // duplicate collapses
        svc.submit(null);        // ignored
        svc.submit("pixel-9");
        svc.flush();

        assertEquals(List.of(List.of(
                "https://damkemon.com/product/iphone-15",
                "https://damkemon.com/product/pixel-9")), sent);

        svc.flush(); // queue drained → no second POST
        assertEquals(1, sent.size());
    }

    @Test
    void submitIsNoOpWhenDisabled() {
        List<List<String>> sent = new ArrayList<>();
        IndexNowService svc = new IndexNowService() {
            @Override void post(List<String> urls) { sent.add(urls); }
        };
        ReflectionTestUtils.setField(svc, "webUrl", "https://damkemon.com");
        svc.submit("iphone-15"); // enabled defaults false
        svc.flush();
        assertTrue(sent.isEmpty());
    }
}
