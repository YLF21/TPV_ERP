package com.tpverp.backend.party.loyalty.sync;

import com.tpverp.backend.sync.SyncOutboxStatus;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record MemberReturnBalanceRecoveryView(
        UUID returnRequestId,
        String action,
        UUID eventId,
        Long eventVersion,
        SyncOutboxStatus eventStatus,
        UUID movementId,
        UUID memberId,
        UUID sourceDocumentId,
        String sourceDocumentNumber,
        UUID returnDocumentId,
        String returnDocumentNumber,
        BigDecimal amount,
        String fingerprint,
        List<ClaimView> claims) {

    public MemberReturnBalanceRecoveryView {
        claims = List.copyOf(claims);
    }

    public record ClaimView(
            UUID lotId,
            UUID sourceMovementId,
            UUID sourceDocumentId,
            BigDecimal amountOriginal,
            BigDecimal amount) {
    }
}
