package com.tpverp.backend.cash;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record CashSessionView(
        UUID id,
        UUID terminalId,
        CashSessionStatus status,
        Instant openedAt,
        BigDecimal openingFund,
        BigDecimal expectedCash,
        BigDecimal availableCash,
        BigDecimal retainedFund,
        BigDecimal discrepancy,
        Instant closedAt,
        Integer reconciliationAttempt,
        boolean closedByAttempt) {
}
