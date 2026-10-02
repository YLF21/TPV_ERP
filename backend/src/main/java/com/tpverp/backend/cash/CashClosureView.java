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
        boolean lateClosing,
        BigDecimal finalWithdrawalAmount,
        java.util.List<CashDenominationCommand> retainedFundDenominations,
        java.util.List<CashDenominationCommand> finalWithdrawalDenominations) {
    public CashClosureView(UUID id, UUID terminalId, String terminalName, UUID closingUserId,
            String closingUserName, String closingUsername, Instant closedAt, BigDecimal expectedCash,
            BigDecimal retainedFund, BigDecimal discrepancy, boolean lateClosing) {
        this(id,terminalId,terminalName,closingUserId,closingUserName,closingUsername,closedAt,
                expectedCash,retainedFund,discrepancy,lateClosing,BigDecimal.ZERO,java.util.List.of(),java.util.List.of());
    }
}
