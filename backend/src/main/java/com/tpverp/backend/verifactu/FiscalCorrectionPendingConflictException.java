package com.tpverp.backend.verifactu;

public final class FiscalCorrectionPendingConflictException extends IllegalStateException {
    public static final String CODE = "subsanacion_pending_conflict";

    public FiscalCorrectionPendingConflictException() {
        super(CODE);
    }
}
