package com.tpverp.backend.cash;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CashAmountCalculatorTest {

    @Test
    void nextOpeningFundDoesNotReapplyMovementsFromBeforeTheLastClose() {
        var terminalId = UUID.randomUUID();
        var storeId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        var sessions = mock(CashSessionRepository.class);
        var movements = mock(CashMovementRepository.class);
        var lastCloseAt = Instant.parse("2026-07-31T18:00:00Z");
        var closed = CashSession.open(
                storeId, terminalId, userId,
                Instant.parse("2026-07-31T08:00:00Z"), new BigDecimal("20.00"));
        closed.close(userId, lastCloseAt, new BigDecimal("50.00"),
                new BigDecimal("30.00"), BigDecimal.ZERO);
        var oldEntry = CashMovement.betweenSessionEntry(
                storeId, terminalId, new BigDecimal("100.00"),
                Instant.parse("2026-07-30T19:00:00Z"), userId, null, "movimiento ya aplicado");
        var newEntry = CashMovement.betweenSessionEntry(
                storeId, terminalId, new BigDecimal("10.00"),
                Instant.parse("2026-07-31T19:00:00Z"), userId, null, "entrada nueva");
        var newWithdrawal = CashMovement.betweenSessionWithdrawal(
                storeId, terminalId, new BigDecimal("5.00"),
                Instant.parse("2026-07-31T20:00:00Z"), userId, null, "retirada nueva");
        when(sessions.findFirstByTerminalIdAndStatusOrderByClosedAtDesc(
                terminalId, CashSessionStatus.CERRADA)).thenReturn(Optional.of(closed));
        when(movements.findAllByTerminalIdAndSesionCajaIsNullOrderByCreadoEnAsc(terminalId))
                .thenReturn(List.of(oldEntry, newEntry, newWithdrawal));

        var result = new CashAmountCalculator(sessions, movements).nextOpeningFund(terminalId);

        assertThat(result).isEqualByComparingTo("35.00");
    }
}
