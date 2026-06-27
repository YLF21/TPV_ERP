package com.tpverp.backend.cash;

import java.math.BigDecimal;

public record CashStoreConfigRequest(
        BigDecimal discrepancyTolerance,
        Boolean requireEntryBreakdown,
        Boolean requireWithdrawalBreakdown,
        Boolean requireClosingBreakdown) {

    void validateComplete() {
        if (discrepancyTolerance == null) {
            throw new IllegalArgumentException("discrepancyTolerance es obligatorio");
        }
        if (requireEntryBreakdown == null) {
            throw new IllegalArgumentException("requireEntryBreakdown es obligatorio");
        }
        if (requireWithdrawalBreakdown == null) {
            throw new IllegalArgumentException("requireWithdrawalBreakdown es obligatorio");
        }
        if (requireClosingBreakdown == null) {
            throw new IllegalArgumentException("requireClosingBreakdown es obligatorio");
        }
    }
}
