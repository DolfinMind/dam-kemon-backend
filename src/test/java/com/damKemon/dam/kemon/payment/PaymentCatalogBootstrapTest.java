package com.damKemon.dam.kemon.payment;

import com.damKemon.dam.kemon.payment.model.PaymentApplication;
import com.damKemon.dam.kemon.payment.model.PaymentProduct;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class PaymentCatalogBootstrapTest {
    @Test void synchronizesRecurringVariantsFromLemonWhileKeepingLifetime() {
        PaymentStore store = mock(PaymentStore.class);
        PaymentService payments = mock(PaymentService.class);
        PaymentProviderAdminService provider = mock(PaymentProviderAdminService.class);
        when(store.application("rewire")).thenReturn(Optional.empty());
        when(store.save(any(PaymentApplication.class))).thenAnswer(call -> call.getArgument(0));
        when(provider.catalog("rewire", 445309, false)).thenReturn(new PaymentProviderAdminService.CatalogView(
                List.of(), List.of(
                variant("2001", "Plus Weekly", "week"),
                variant("2002", "Pro Monthly", "month"),
                variant("2003", "Premium Yearly", "year")
        ), Instant.now()));
        PaymentCatalogBootstrap bootstrap = new PaymentCatalogBootstrap(store, payments, provider,
                445309, 1276394, 1995479, false, "https://rewire.example/success");

        bootstrap.run(null);

        verify(payments).upsertProduct("rewire", "lifetime", "rewire_pro", 445309, 1276394, 1995479,
                false, null, 0, true, false, true, "https://rewire.example/success");
        verify(payments).upsertProduct("rewire", "plus_weekly", "rewire_plus", 445309, 1276394, 2001,
                true, "week", 1, true, false, true, "https://rewire.example/success");
        verify(payments).upsertProduct("rewire", "pro_monthly", "rewire_pro", 445309, 1276394, 2002,
                true, "month", 1, true, false, true, "https://rewire.example/success");
        verify(payments).upsertProduct("rewire", "premium_yearly", "rewire_premium", 445309, 1276394, 2003,
                true, "year", 1, true, false, true, "https://rewire.example/success");
    }

    @Test void neverRepointsAnEstablishedCodeToAnotherVariant() {
        PaymentStore store = mock(PaymentStore.class);
        PaymentService payments = mock(PaymentService.class);
        PaymentProviderAdminService provider = mock(PaymentProviderAdminService.class);
        PaymentApplication app = PaymentApplication.builder().appId("rewire").build();
        PaymentProduct existing = PaymentProduct.builder().appId("rewire").code("pro_monthly")
                .variantId(1999).active(true).build();
        when(store.application("rewire")).thenReturn(Optional.of(app));
        when(store.product("rewire", "pro_monthly")).thenReturn(Optional.of(existing));
        when(store.save(any(PaymentApplication.class))).thenAnswer(call -> call.getArgument(0));
        when(provider.catalog("rewire", 445309, false)).thenReturn(new PaymentProviderAdminService.CatalogView(
                List.of(), List.of(variant("2002", "Pro Monthly", "month")), Instant.now()));
        PaymentCatalogBootstrap bootstrap = new PaymentCatalogBootstrap(store, payments, provider,
                445309, 1276394, 1995479, false, "https://rewire.example/success");

        bootstrap.run(null);

        verify(payments).upsertProduct("rewire", "lifetime", "rewire_pro", 445309, 1276394, 1995479,
                false, null, 0, true, false, true, "https://rewire.example/success");
        verifyNoMoreInteractions(payments);
    }

    private static PaymentProviderAdminService.ProviderVariant variant(String id, String name, String interval) {
        return new PaymentProviderAdminService.ProviderVariant(id, "1276394", name, "published", 100L,
                true, interval, 1, true, 3, false, false);
    }
}
