package com.tpverp.saas.sync;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Authoritative final return snapshot shared by online finalize and sync recovery. */
public record MemberReturnBalanceRecoveryCommand(
        UUID operationId,
        UUID companyId,
        UUID storeId,
        UUID memberId,
        UUID reservationId,
        String reservationSaleId,
        UUID sourceDocumentId,
        UUID returnDocumentId,
        BigDecimal attributedAmount,
        String fingerprint,
        List<Claim> claims) {
    public MemberReturnBalanceRecoveryCommand {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(companyId, "companyId");
        Objects.requireNonNull(storeId, "storeId");
        Objects.requireNonNull(memberId, "memberId");
        Objects.requireNonNull(sourceDocumentId, "sourceDocumentId");
        Objects.requireNonNull(attributedAmount, "attributedAmount");
        Objects.requireNonNull(fingerprint, "fingerprint");
        claims = List.copyOf(Objects.requireNonNull(claims, "claims"));
    }

    public record Claim(UUID lotId, UUID sourceMovementId, UUID sourceDocumentId,
            BigDecimal amountOriginal, BigDecimal amount) {}
}
