package com.tpverp.backend.security.sales;

import java.time.Instant;
import java.util.Objects;

public final class SaleOperationAuthorizationThrottledException
        extends RuntimeException {

    public static final String CODE = "SALE_OPERATION_AUTHORIZATION_THROTTLED";

    private final Instant blockedUntil;
    private final long retryAfterSeconds;

    public SaleOperationAuthorizationThrottledException(
            Instant blockedUntil,
            long retryAfterSeconds) {
        super("Hay demasiados intentos de autorizacion operativa");
        this.blockedUntil = Objects.requireNonNull(blockedUntil, "blockedUntil");
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    }

    public Instant blockedUntil() {
        return blockedUntil;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
