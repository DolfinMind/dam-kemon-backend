package com.damKemon.dam.kemon.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The scam registry lives or dies on identifier canonicalization — the same
 * seller written five ways must collapse to one key. No Spring context needed.
 */
class ProtectServiceTest {

    @Test
    void phonesNormalise() {
        assertEquals("phone:01712345678", ProtectService.canonicalIdentifier("01712-345678"));
        assertEquals("phone:01712345678", ProtectService.canonicalIdentifier("+880 1712 345678"));
        assertEquals("phone:01712345678", ProtectService.canonicalIdentifier("8801712345678"));
    }

    @Test
    void facebookPagesCollapseToHandle() {
        assertEquals("fb:gadgetparadise", ProtectService.canonicalIdentifier("https://www.facebook.com/GadgetParadise"));
        assertEquals("fb:gadgetparadise", ProtectService.canonicalIdentifier("fb.com/gadgetparadise/?ref=share"));
        assertEquals("fb:gadgetparadise", ProtectService.canonicalIdentifier("m.facebook.com/gadgetparadise/posts/123"));
        assertEquals("fb:100091234", ProtectService.canonicalIdentifier("facebook.com/profile.php?id=100091234"));
        assertEquals("ig:gadget.para", ProtectService.canonicalIdentifier("https://instagram.com/gadget.para"));
    }

    @Test
    void websitesCollapseToHost() {
        assertEquals("host:startech.com.bd", ProtectService.canonicalIdentifier("https://www.startech.com.bd/apple-iphone"));
        assertEquals("host:startech.com.bd", ProtectService.canonicalIdentifier("startech.com.bd"));
        assertEquals("host:daraz.com.bd", ProtectService.canonicalIdentifier("HTTPS://DARAZ.COM.BD/products/x?spm=1"));
    }

    @Test
    void garbageIsRejected() {
        assertNull(ProtectService.canonicalIdentifier(null));
        assertNull(ProtectService.canonicalIdentifier("   "));
        assertNull(ProtectService.canonicalIdentifier("hello there"));
        assertNull(ProtectService.canonicalIdentifier("facebook.com"));
        assertNull(ProtectService.canonicalIdentifier("12345"));
    }
}
