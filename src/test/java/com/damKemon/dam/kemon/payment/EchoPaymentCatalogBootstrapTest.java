package com.damKemon.dam.kemon.payment;

import com.damKemon.dam.kemon.payment.model.PaymentApplication;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EchoPaymentCatalogBootstrapTest {
    @Test void registersEchoProAndFixedDiamondPacks() {
        PaymentStore store = mock(PaymentStore.class);
        PaymentService payments = mock(PaymentService.class);
        when(store.application("echo-memory")).thenReturn(Optional.empty());
        when(store.save(any(PaymentApplication.class))).thenAnswer(call -> call.getArgument(0));
        EchoPaymentCatalogBootstrap bootstrap = new EchoPaymentCatalogBootstrap(
                store, payments, 445309, 1279900, 2001240, 2001241,
                1279894, 2001228, 2001234, 2001235, true, "https://echo.example/success");

        bootstrap.run(null);

        verify(payments).upsertProduct("echo-memory", "pro_monthly", "echo_pro",
                445309, 1279900, 2001240, true, "month", 1, true, true, true,
                "https://echo.example/success");
        verify(payments).upsertProduct("echo-memory", "lifetime", "echo_pro",
                445309, 1279900, 2001241, false, null, 0, true, true, true,
                "https://echo.example/success");
        verify(payments).upsertProduct("echo-memory", "diamonds_40", "echo_diamonds_40",
                445309, 1279894, 2001228, false, null, 0, false, true, true,
                "https://echo.example/success");
        verify(payments).upsertProduct("echo-memory", "diamonds_100", "echo_diamonds_100",
                445309, 1279894, 2001234, false, null, 0, false, true, true,
                "https://echo.example/success");
        verify(payments).upsertProduct("echo-memory", "diamonds_250", "echo_diamonds_250",
                445309, 1279894, 2001235, false, null, 0, false, true, true,
                "https://echo.example/success");
    }
}
