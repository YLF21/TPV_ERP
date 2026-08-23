package com.tpverp.backend.document;

import java.util.Objects;

public final class PaymentSessionClosedException extends IllegalStateException {

    public static final String CODE = "PAYMENT_SESSION_CLOSED";

    private final SalePaymentSessionStatus status;

    public PaymentSessionClosedException(SalePaymentSessionStatus status) {
        super("payment_session_" + Objects.requireNonNull(status, "status").name().toLowerCase());
        this.status = status;
    }

    public SalePaymentSessionStatus status() {
        return status;
    }

    public boolean retryable() {
        return status == SalePaymentSessionStatus.CANCELLED;
    }
}
