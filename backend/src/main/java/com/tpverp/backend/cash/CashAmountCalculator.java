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

    // Calculates theoretical cash using only cash movements for the session.
    public BigDecimal expectedCash(CashSession session, List<CashMovement> movements) {
        var total = Money.euros(session.getOpeningFund());
        for (var movement : movements) {
            total = switch (movement.getType()) {
                case COBRO_EFECTIVO, ENTRADA -> total.add(movement.getAmount());
                case DEVOLUCION_EFECTIVO, RETIRADA, RETIRADA_CIERRE -> total.subtract(movement.getAmount());
                case ENTRADA_ENTRE_SESIONES, RETIRADA_ENTRE_SESIONES -> total;
            };
        }
        return Money.euros(total);
    }

    // Calculates the next opening fund from the last closed session and pending movements.
    @Transactional(readOnly = true)
    public BigDecimal nextOpeningFund(UUID terminalId) {
        var retainedFund = sessions.findFirstByTerminalIdAndStatusOrderByClosedAtDesc(
                        terminalId, CashSessionStatus.CERRADA)
                .map(CashSession::getRetainedFund)
                .orElse(Money.euros("0"));
        var betweenSessions = movements.findAllByTerminalIdAndSesionCajaIsNullOrderByCreadoEnAsc(terminalId)
                .stream()
                .map(movement -> switch (movement.getType()) {
                    case ENTRADA_ENTRE_SESIONES -> movement.getAmount();
                    case RETIRADA_ENTRE_SESIONES -> movement.getAmount().negate();
                    case COBRO_EFECTIVO, DEVOLUCION_EFECTIVO, ENTRADA, RETIRADA, RETIRADA_CIERRE -> Money.euros("0");
                })
                .reduce(Money.euros("0"), BigDecimal::add);
        return Money.euros(retainedFund.add(betweenSessions));
    }

    // Returns the cash currently available in an open session.
    @Transactional(readOnly = true)
    public BigDecimal availableCash(CashSession session) {
        return expectedCash(session, movements.findAllBySesionCajaId(session.getId()));
    }
}
