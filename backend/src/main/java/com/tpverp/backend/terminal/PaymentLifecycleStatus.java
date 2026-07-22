package com.tpverp.backend.terminal;

/** Stable business lifecycle exposed to clients independently of provider details. */
public enum PaymentLifecycleStatus {
    INICIADO,
    PROCESANDO,
    APROBADO,
    RECHAZADO,
    INCIERTO,
    CANCELADO;

    public static PaymentLifecycleStatus from(PaymentTerminalOperation operation) {
        if (operation.getStatus() == PaymentTerminalOperationStatus.ERROR) {
            return operation.isFinalOutcome() ? RECHAZADO : INCIERTO;
        }
        return from(operation.getStatus());
    }

    public static PaymentLifecycleStatus from(PaymentTerminalOperationStatus status) {
        return switch (status) {
            case PENDING -> INICIADO;
            case SENT -> PROCESANDO;
            case APPROVED, REFUNDED, PARTIALLY_REFUNDED -> APROBADO;
            case DECLINED -> RECHAZADO;
            case TIMEOUT, REVIEW_REQUIRED, ERROR -> INCIERTO;
            case CANCELLED -> CANCELADO;
        };
    }

    public boolean blocksAnotherCharge() {
        return this == INICIADO || this == PROCESANDO || this == INCIERTO;
    }
}
