package com.tpverp.bridge.spi;

public record OperationResult(
        boolean approved,
        String code,
        String reference,
        String authorization,
        String message,
        String receiptText) {

    public static OperationResult failure(String code, String message) {
        return new OperationResult(false, code, null, null, message, null);
    }
}
