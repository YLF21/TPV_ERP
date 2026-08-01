package com.tpverp.backend.cash;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record CashClosureView(
        UUID id,
        UUID terminalId,
        String terminalName,
        UUID closingUserId,
        String closingUserName,
        String closingUsername,
        Instant closedAt,
        BigDecimal expectedCash,
        BigDecimal retainedFund,
        BigDecimal discrepancy,
        boolean lateClosing) {
}
