package com.tpverp.backend.terminal;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

public record CardTerminalRequest(
        UUID checkoutId,
        UUID terminalId,
        PaymentTerminalProvider provider,
        BigDecimal amount,
        boolean testMode) {

    public CardTerminalRequest {
        Objects.requireNonNull(checkoutId, "checkoutId");
        Objects.requireNonNull(terminalId, "terminalId");
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(amount, "amount");
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
    }
}
