package com.damKemon.dam.kemon.service;

import com.damKemon.dam.kemon.model.Product;
import com.damKemon.dam.kemon.model.WishlistItem;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EmailNotifierTest {
    @Test void returnsProviderOutcomeAndUsesDeliveryKey() {
        ResendService resend = mock(ResendService.class); when(resend.sendEmail(any(),any(),any(),eq("stable-key"))).thenReturn(true);
        EmailNotifier notifier = new EmailNotifier(resend); ReflectionTestUtils.setField(notifier,"siteUrl","https://example.test");
        assertTrue(notifier.sendPriceDropAlert("u@test", Product.builder().id("p").name("Thing").build(), WishlistItem.builder().build(), 90, "stable-key"));
        verify(resend).sendEmail(any(), contains("Price alert"), any(), eq("stable-key"));
    }
}
