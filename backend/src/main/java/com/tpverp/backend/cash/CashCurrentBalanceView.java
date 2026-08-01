package com.tpverp.backend.cash;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record CashCurrentBalanceView(
        UUID terminalId,
        String terminalName,
        CashCurrentBalanceStatus status,
        UUID openingUserId,
        String openingUserName,
        String openingUsername,
        Instant openedAt,
        BigDecimal expectedCash,
        Instant lastActivityAt) {
}
