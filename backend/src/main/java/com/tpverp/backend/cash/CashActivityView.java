package com.tpverp.backend.cash;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record CashActivityView(String id, Instant occurredAt, UUID userId, String username, String userName,
        String action, String concept, BigDecimal amount, BigDecimal balance, String reference,
        UUID sessionId, CashSessionStatus cashState, String sourceReference,
        UUID terminalId, String terminalCode, String terminalName) {
    CashActivityView withoutAmounts() {
        return new CashActivityView(id, occurredAt, userId, username, userName, action, concept,
                null, null, reference, sessionId, cashState, sourceReference, terminalId, terminalCode, terminalName);
    }
}
