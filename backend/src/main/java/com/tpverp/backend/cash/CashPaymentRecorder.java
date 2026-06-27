package com.tpverp.backend.cash;

import com.tpverp.backend.document.CommercialDocument;
import com.tpverp.backend.document.DocumentPayment;
import com.tpverp.backend.document.Money;
import com.tpverp.backend.organization.CurrentOrganization;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

@Service
public class CashPaymentRecorder {

    private static final String CASH_METHOD = "EFECTIVO";

    private final CashSessionRepository sessions;
    private final CashMovementRepository movements;
    private final CurrentOrganization organization;
    private final Clock clock;

    public CashPaymentRecorder(
            CashSessionRepository sessions,
            CashMovementRepository movements,
            CurrentOrganization organization,
            Clock clock) {
        this.sessions = sessions;
        this.movements = movements;
        this.organization = organization;
        this.clock = clock;
    }

    // Exige una sesion abierta para la terminal autenticada antes de confirmar cobros.
    public void requireOpenSession(UUID terminalId) {
        openSession(terminalId);
    }

    // Registra solo pagos en efectivo positivos asociados al documento persistido.
    public void recordDocumentPayments(UUID terminalId, CommercialDocument document) {
        var session = openSession(terminalId);
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        var user = organization.currentUser(authentication);
        for (var payment : document.getPagos()) {
            // documento_pago_id hace idempotente el registro al reintentar la misma operacion.
            if (isRecordableCashPayment(payment)
                    && !movements.existsByDocumentoPagoId(payment.getId())) {
                movements.save(CashMovement.sessionMovement(
                        session.getStoreId(),
                        session.getTerminalId(),
                        session,
                        CashMovementType.COBRO_EFECTIVO,
                        payment.getImporte(),
                        Instant.now(clock),
                        user.getId(),
                        null,
                        null,
                        document.getId(),
                        payment.getId()));
            }
        }
    }

    private CashSession openSession(UUID terminalId) {
        return sessions.findByTerminalIdAndStatus(terminalId, CashSessionStatus.ABIERTA)
                .orElseThrow(() -> new IllegalStateException("No hay una sesion de caja abierta"));
    }

    private boolean isRecordableCashPayment(DocumentPayment payment) {
        return CASH_METHOD.equals(payment.getMetodoPago().getNombre())
                && Money.euros(payment.getImporte()).signum() > 0;
    }
}
