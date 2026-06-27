package com.tpverp.backend.cash;

import java.math.BigDecimal;

public record CashStoreConfigRequest(
        BigDecimal discrepancyTolerance,
        boolean requireEntryBreakdown,
        boolean requireWithdrawalBreakdown,
        boolean requireClosingBreakdown) {
}
