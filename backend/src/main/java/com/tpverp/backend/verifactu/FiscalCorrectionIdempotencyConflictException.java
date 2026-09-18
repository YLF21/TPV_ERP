package com.tpverp.backend.verifactu;

public final class FiscalCorrectionIdempotencyConflictException extends IllegalStateException {
    public static final String CODE = "subsanacion_idempotency_conflict";

    public FiscalCorrectionIdempotencyConflictException() {
        super(CODE);
    }
}
