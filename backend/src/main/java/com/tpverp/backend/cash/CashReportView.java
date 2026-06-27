package com.tpverp.backend.cash;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record CashReportView(
        UUID terminalId,
        UUID storeId,
        Instant from,
        Instant to,
        Map<CashMovementType, BigDecimal> totalsByType,
        BigDecimal retainedFunds,
        BigDecimal discrepancies) {
}
