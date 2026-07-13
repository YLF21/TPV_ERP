package com.tpverp.backend.terminal;

import java.util.Objects;

public record CardTerminalResult(
        PaymentTerminalOperationStatus status,
        String reference,
        String authorization,
        String message) {

    public CardTerminalResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(message, "message");
    }
}
