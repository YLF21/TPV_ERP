package com.tpverp.backend.document;

import com.tpverp.backend.audit.AuditResult;
import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.party.loyalty.central.MemberBalanceManualReconciliationRequiredException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MemberBalanceRecoveryIncidentService {

    static final int MAX_ATTEMPTS = 10;
    private static final Duration INITIAL_BACKOFF = Duration.ofSeconds(5);
    private static final Duration MAXIMUM_BACKOFF = Duration.ofMinutes(15);

    private final SalePaymentSessionRepository sessions;
    private final CurrentOrganization organization;
    private final AuditService audit;
    private final Clock clock;

    public MemberBalanceRecoveryIncidentService(
            SalePaymentSessionRepository sessions,
            CurrentOrganization organization,
            AuditService audit,
            Clock clock) {
        this.sessions = sessions;
        this.organization = organization;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<MemberBalanceRecoveryIncidentView> list() {
        UUID storeId = organization.currentStore().getId();
        return sessions.findMemberBalanceRecoveryIncidents(
                        storeId, PageRequest.of(0, 100)).stream()
                .map(MemberBalanceRecoveryIncidentView::from)
                .toList();
    }

    @Transactional
    public void recordFailure(UUID sessionId, RuntimeException failure) {
        SalePaymentSession session = sessions.findLocked(sessionId).orElse(null);
        if (session == null
                || session.getMemberBalanceReservationId() == null
                || session.getMemberBalanceSynchronizedAt() != null) {
            return;
        }
        var now = clock.instant();
        int nextAttempt = session.getMemberBalanceRecoveryAttempts() + 1;
        MemberBalanceRecoveryDisposition disposition =
                failure instanceof MemberBalanceManualReconciliationRequiredException
                        ? MemberBalanceRecoveryDisposition.MANUAL_RECONCILIATION_REQUIRED
                        : MemberBalanceRecoveryDisposition.AUTOMATIC_RETRY;
        session.recordMemberBalanceRecoveryFailure(
                message(failure), now, now.plus(backoff(nextAttempt)), MAX_ATTEMPTS, disposition);
    }

    @Transactional
    public MemberBalanceRecoveryIncidentView scheduleManualRetry(
            UUID sessionId,
            long expectedVersion,
            String reason) {
        String normalizedReason = requireReason(reason);
        UUID storeId = organization.currentStore().getId();
        SalePaymentSession session = sessions.findLocked(sessionId)
                .filter(candidate -> candidate.getStoreId().equals(storeId))
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Incidencia de saldo del miembro no encontrada"));
        if (session.getVersion() != expectedVersion) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "La incidencia ha cambiado; actualice el listado antes de reintentar");
        }
        if (session.getMemberBalanceReservationId() == null
                || session.getMemberBalanceSynchronizedAt() != null) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "La incidencia ya no esta pendiente");
        }
        if (session.getMemberBalanceRecoveryDisposition()
                == MemberBalanceRecoveryDisposition.MANUAL_RECONCILIATION_REQUIRED) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "La incidencia requiere conciliacion contable manual y no admite reintento");
        }
        session.scheduleMemberBalanceManualRetry(clock.instant());
        audit.record(
                "MEMBER_BALANCE_RECOVERY_MANUAL_RETRY",
                AuditResult.EXITO,
                Map.of(
                        "paymentSessionId", session.getId().toString(),
                        "reservationId", session.getMemberBalanceReservationId().toString(),
                        "recoveryKind", session.getTicketId() == null
                                ? "ABORT" : "FINALIZATION",
                        "previousAttempts", session.getMemberBalanceRecoveryAttempts(),
                        "reason", normalizedReason));
        return MemberBalanceRecoveryIncidentView.from(session);
    }

    private static Duration backoff(int attempt) {
        long delay = INITIAL_BACKOFF.toMillis();
        long maximum = MAXIMUM_BACKOFF.toMillis();
        for (int current = 1; current < attempt && delay < maximum; current++) {
            delay = delay > maximum / 2 ? maximum : Math.min(maximum, delay * 2);
        }
        return Duration.ofMillis(delay);
    }

    private static String message(RuntimeException failure) {
        String value = failure == null ? null : failure.getMessage();
        return value == null || value.isBlank()
                ? failure == null ? "Unknown recovery error" : failure.getClass().getSimpleName()
                : value;
    }

    private static String requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "El motivo del reintento es obligatorio");
        }
        String normalized = reason.trim();
        if (normalized.length() > 500) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "El motivo no puede superar 500 caracteres");
        }
        return normalized;
    }
}
