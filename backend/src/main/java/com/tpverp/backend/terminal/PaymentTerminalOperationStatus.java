package com.tpverp.backend.terminal;

public enum PaymentTerminalOperationStatus {
    PENDING,
    SENT,
    APPROVED,
    DECLINED,
    CANCELLED,
    REFUNDED,
    PARTIALLY_REFUNDED,
    TIMEOUT,
    ERROR,
    REVIEW_REQUIRED
}
