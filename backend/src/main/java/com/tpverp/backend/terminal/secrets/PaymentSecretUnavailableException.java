package com.tpverp.backend.terminal.secrets;

public final class PaymentSecretUnavailableException extends IllegalStateException {
    public PaymentSecretUnavailableException(String message) { super(message); }
    public PaymentSecretUnavailableException(String message, Throwable cause) { super(message, cause); }
}
