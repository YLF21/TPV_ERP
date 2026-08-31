package com.tpverp.backend.document;

import java.time.Clock;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class MemberBalanceCheckoutRecoveryWorker {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(MemberBalanceCheckoutRecoveryWorker.class);

    private final SalePaymentSessionRepository sessions;
    private final SalePaymentSessionService paymentSessions;
    private final MemberBalanceRecoveryIncidentService incidents;
    private final Clock clock;

    @Autowired
    public MemberBalanceCheckoutRecoveryWorker(
            SalePaymentSessionRepository sessions,
            SalePaymentSessionService paymentSessions,
            MemberBalanceRecoveryIncidentService incidents,
            Clock clock) {
        this.sessions = sessions;
        this.paymentSessions = paymentSessions;
        this.incidents = incidents;
        this.clock = clock;
    }

    MemberBalanceCheckoutRecoveryWorker(
            SalePaymentSessionRepository sessions,
            SalePaymentSessionService paymentSessions,
            MemberBalanceRecoveryIncidentService incidents) {
        this(sessions, paymentSessions, incidents, Clock.systemUTC());
    }

    @Scheduled(fixedDelayString =
            "${tpv.members.balance-checkout-recovery-delay-ms:5000}")
    public void recover() {
        var page = PageRequest.of(0, 100);
        var now = clock.instant();
        sessions.findMemberBalanceFinalizationRecoveryCandidates(now, page)
                .forEach(session -> recoverFinalization(session.getId()));
        sessions.findMemberBalanceAbortRecoveryCandidates(now, page)
                .forEach(session -> recoverAbort(session.getId()));
    }

    private void recoverFinalization(UUID sessionId) {
        try {
            paymentSessions.recoverMemberBalanceFinalization(sessionId);
        } catch (RuntimeException error) {
            incidents.recordFailure(sessionId, error);
            LOGGER.warn("No se pudo recuperar la finalizacion de saldo del miembro de la sesion {}: {}",
                    sessionId, message(error));
        }
    }

    private void recoverAbort(UUID sessionId) {
        try {
            paymentSessions.recoverMemberBalanceAbort(sessionId);
        } catch (RuntimeException error) {
            incidents.recordFailure(sessionId, error);
            LOGGER.warn("No se pudo recuperar el aborto de saldo del miembro de la sesion {}: {}",
                    sessionId, message(error));
        }
    }

    private static String message(RuntimeException error) {
        return error.getMessage() == null || error.getMessage().isBlank()
                ? error.getClass().getSimpleName()
                : error.getMessage();
    }
}
