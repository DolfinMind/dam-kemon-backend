package com.damKemon.dam.kemon.controller;

import org.junit.jupiter.api.Test;
import jakarta.servlet.http.Cookie;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AffiliateControllerTest {

    @Test
    void sameSiteCookieCarriesAnonymousAttributionWithoutCustomHeaders() {
        assertEquals("cookie-id", AffiliateController.anonIdFrom(
                new Cookie[]{new Cookie("dk_anon_id", "cookie-id")}, "header-id"));
        assertEquals("header-id", AffiliateController.anonIdFrom(null, "header-id"));
        assertNull(AffiliateController.anonIdFrom(null, null));
    }

    @Test
    void anonymousAttributionIsBounded() {
        assertEquals(64, AffiliateController.anonIdFrom(
                new Cookie[]{new Cookie("dk_anon_id", "x".repeat(100))}, null).length());
    }
}
