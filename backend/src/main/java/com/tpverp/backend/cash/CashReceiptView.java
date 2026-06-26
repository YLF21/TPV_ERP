package com.tpverp.backend.cash;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record CashReceiptView(
        UUID movementId,
        UUID sessionId,
        UUID terminalId,
        String terminalName,
        Instant createdAt,
        String userName,
        BigDecimal amount,
        List<CashDenominationCommand> denominations,
        BigDecimal retainedFund,
        BigDecimal discrepancy,
        BigDecimal expectedCash,
        String giverSignatureLabel,
        String receiverSignatureLabel) {
}
