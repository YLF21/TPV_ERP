package com.tpverp.backend.cash;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record CashMovementView(
        UUID id,
        UUID terminalId,
        UUID sessionId,
        CashMovementType type,
        BigDecimal amount,
        Instant createdAt,
        UUID userId,
        UUID authorizerUserId,
        String comment) {

    static CashMovementView from(CashMovement movement) {
        return new CashMovementView(
                movement.getId(),
                movement.getTerminalId(),
                movement.getSessionId(),
                movement.getType(),
                movement.getAmount(),
                movement.getCreatedAt(),
                movement.getUserId(),
                movement.getAuthorizerUserId(),
                movement.getComment());
    }
}
