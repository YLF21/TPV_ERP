package com.tpverp.backend.document;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record MemberBalanceRecoveryIncidentView(
        UUID sessionId,
        String recoveryKind,
        SalePaymentSessionStatus paymentStatus,
        UUID ticketId,
        String ticketNumber,
        UUID reservationId,
        BigDecimal requestedAmount,
        BigDecimal appliedAmount,
        int attempts,
        Instant lastAttemptAt,
        Instant nextAttemptAt,
        String lastError,
        boolean manualReviewRequired,
        MemberBalanceRecoveryDisposition disposition,
        boolean retryAllowed,
        Instant updatedAt,
        long version) {

    static MemberBalanceRecoveryIncidentView from(SalePaymentSession session) {
        return new MemberBalanceRecoveryIncidentView(
                session.getId(),
                session.getTicketId() == null ? "ABORT" : "FINALIZATION",
                session.getStatus(),
                session.getTicketId(),
                session.getTicketNumber(),
                session.getMemberBalanceReservationId(),
                session.getMemberBalanceRequestedAmount(),
                session.getMemberBalanceAppliedAmount(),
                session.getMemberBalanceRecoveryAttempts(),
                session.getMemberBalanceRecoveryLastAttemptAt(),
                session.getMemberBalanceRecoveryNextAttemptAt(),
                session.getMemberBalanceRecoveryLastError(),
                session.isMemberBalanceRecoveryManualReview(),
                session.getMemberBalanceRecoveryDisposition(),
                session.getMemberBalanceRecoveryDisposition()
                        != MemberBalanceRecoveryDisposition.MANUAL_RECONCILIATION_REQUIRED,
                session.getUpdatedAt(),
                session.getVersion());
    }
}
