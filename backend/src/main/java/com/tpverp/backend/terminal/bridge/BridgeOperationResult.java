package com.tpverp.backend.terminal.bridge;

public record BridgeOperationResult(
        boolean approved,
        String code,
        String reference,
        String authorization,
        String message,
        String receiptText) {
    public BridgeOperationResult(boolean approved, String code, String reference) {
        this(approved, code, reference, null, null, null);
    }
}
