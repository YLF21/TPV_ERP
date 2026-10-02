package com.tpverp.backend.cash;

import java.math.BigDecimal;
import java.util.List;

public record CashSalesSessionReadinessView(
        boolean cashSessionRequired,
        boolean open,
        CashSessionView session,
        boolean requireEntryBreakdown,
        List<BigDecimal> entryDenominations,
        boolean requireWithdrawalBreakdown,
        List<BigDecimal> withdrawalDenominations,
        boolean requireClosingBreakdown,
        List<BigDecimal> closingDenominations) {
    public CashSalesSessionReadinessView(boolean cashSessionRequired, boolean open, CashSessionView session,
            boolean requireEntryBreakdown, List<BigDecimal> entryDenominations,
            boolean requireWithdrawalBreakdown, List<BigDecimal> withdrawalDenominations) {
        this(cashSessionRequired, open, session, requireEntryBreakdown, entryDenominations,
                requireWithdrawalBreakdown, withdrawalDenominations, false, CashDenomination.valuesInEuroOrder());
    }
}
