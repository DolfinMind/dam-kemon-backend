package com.damKemon.dam.kemon.payment;

public class PaymentProviderException extends RuntimeException {
    public PaymentProviderException(String message) { super(message); }
    public PaymentProviderException(String message, Throwable cause) { super(message, cause); }
}
