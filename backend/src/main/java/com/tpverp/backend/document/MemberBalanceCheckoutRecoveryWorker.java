package com.tpverp.backend.document;

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

    public MemberBalanceCheckoutRecoveryWorker(
            SalePaymentSessionRepository sessions,
            SalePaymentSessionService paymentSessions) {
        this.sessions = sessions;
        this.paymentSessions = paymentSessions;
    }

    @Scheduled(fixedDelayString =
            "${tpv.members.balance-checkout-recovery-delay-ms:5000}")
    public void recover() {
        sessions.findTop100ByTicketIdIsNotNullAndMemberBalanceReservationIdIsNotNullAndMemberBalanceSynchronizedAtIsNullOrderByUpdatedAtAsc()
                .forEach(session -> recoverFinalization(session.getId()));
        sessions.findTop100ByStatusAndTicketIdIsNullAndMemberBalanceReservationIdIsNotNullAndMemberBalanceSynchronizedAtIsNullOrderByUpdatedAtAsc(
                        SalePaymentSessionStatus.CANCELLED)
                .forEach(session -> recoverAbort(session.getId()));
    }

    private void recoverFinalization(java.util.UUID sessionId) {
        try {
            paymentSessions.recoverMemberBalanceFinalization(sessionId);
        } catch (RuntimeException error) {
            LOGGER.warn(
                    "No se pudo recuperar la finalizacion de saldo socio de la sesion {}",
                    sessionId,
                    error);
        }
    }

    private void recoverAbort(java.util.UUID sessionId) {
        try {
            paymentSessions.recoverMemberBalanceAbort(sessionId);
        } catch (RuntimeException error) {
            LOGGER.warn(
                    "No se pudo recuperar el aborto de saldo socio de la sesion {}",
                    sessionId,
                    error);
        }
    }
}
