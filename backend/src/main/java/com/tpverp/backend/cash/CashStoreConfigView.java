package com.tpverp.backend.cash;

import java.math.BigDecimal;
import java.util.UUID;

public record CashStoreConfigView(
        UUID storeId,
        BigDecimal discrepancyTolerance,
        boolean requireEntryBreakdown,
        boolean requireWithdrawalBreakdown,
        boolean requireClosingBreakdown) {

    static CashStoreConfigView from(CashStoreConfig config) {
        return new CashStoreConfigView(
                config.getStoreId(),
                config.getDiscrepancyTolerance(),
                config.isRequireEntryBreakdown(),
                config.isRequireWithdrawalBreakdown(),
                config.isRequireClosingBreakdown());
    }
}
