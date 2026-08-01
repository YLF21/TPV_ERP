package com.tpverp.backend.cash;

import java.math.BigDecimal;
import java.util.UUID;

public record CashCloseOperationView(
        UUID operationId,
        UUID sessionId,
        UUID terminalId,
        CashCloseOperationStatus status,
        BigDecimal finalWithdrawalAmount,
        String finalWithdrawalComment,
        UUID latestReconciliationAttemptId,
        CashSessionView result) {
}
