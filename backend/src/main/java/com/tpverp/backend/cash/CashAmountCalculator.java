package com.tpverp.backend.cash;

import com.tpverp.backend.document.Money;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CashAmountCalculator {

    private final CashSessionRepository sessions;
    private final CashMovementRepository movements;

    public CashAmountCalculator(CashSessionRepository sessions, CashMovementRepository movements) {
        this.sessions = sessions;
        this.movements = movements;
    }

    // Calcula el efectivo teorico de una sesion usando solo movimientos de efectivo.
    public BigDecimal expectedCash(CashSession session, List<CashMovement> movements) {
        var total = Money.euros(session.getOpeningFund());
        for (var movement : movements) {
            total = switch (movement.getType()) {
                case COBRO_EFECTIVO, ENTRADA -> total.add(movement.getAmount());
                case DEVOLUCION_EFECTIVO, RETIRADA, RETIRADA_CIERRE -> total.subtract(movement.getAmount());
                case ENTRE_SESIONES -> total;
            };
        }
        return Money.euros(total);
    }

    // Calcula el fondo inicial para la siguiente apertura con la ultima caja cerrada y movimientos pendientes.
    @Transactional(readOnly = true)
    public BigDecimal nextOpeningFund(UUID terminalId) {
        var retainedFund = sessions.findFirstByTerminalIdAndStatusOrderByClosedAtDesc(
                        terminalId, CashSessionStatus.CERRADA)
                .map(CashSession::getRetainedFund)
                .orElse(Money.euros("0"));
        var betweenSessions = movements.findAllByTerminalIdAndSesionCajaIsNullOrderByCreadoEnAsc(terminalId)
                .stream()
                .filter(movement -> movement.getType() == CashMovementType.ENTRE_SESIONES)
                .map(CashMovement::getAmount)
                .reduce(Money.euros("0"), BigDecimal::add);
        return Money.euros(retainedFund.add(betweenSessions));
    }

    // Devuelve el efectivo actualmente disponible en una sesion abierta.
    @Transactional(readOnly = true)
    public BigDecimal availableCash(CashSession session) {
        return expectedCash(session, movements.findAllBySesionCajaId(session.getId()));
    }
}
