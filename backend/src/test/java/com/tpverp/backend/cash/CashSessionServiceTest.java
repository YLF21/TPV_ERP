package com.tpverp.backend.cash;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CashSessionServiceTest {

    @Test
    void secondMismatchClosesSessionAndStoresDiscrepancy() {
        var session = openSession();

        session.registerAttempt(
                UUID.randomUUID(), Instant.parse("2026-06-25T10:00:00Z"),
                new BigDecimal("93.333"), new BigDecimal("100.00"), new BigDecimal("1.00"));
        var second = session.registerAttempt(
                UUID.randomUUID(), Instant.parse("2026-06-25T10:05:00Z"),
                new BigDecimal("94.00"), new BigDecimal("100.00"), new BigDecimal("1.00"));

        assertThat(session.getStatus()).isEqualTo(CashSessionStatus.CERRADA);
        assertThat(session.getClosedAt()).isEqualTo(Instant.parse("2026-06-25T10:05:00Z"));
        assertThat(session.getDiscrepancy()).isEqualByComparingTo("-6.00");
        assertThat(second.closedSession()).isTrue();
        assertThat(second.getAttemptNumber()).isEqualTo(2);
        assertThat(second.getDiscrepancy()).isEqualByComparingTo("-6.00");
        assertThat(session.getAttempts()).extracting(CashReconciliationAttempt::getDiscrepancy)
                .containsExactly(new BigDecimal("-6.67"), new BigDecimal("-6.00"));
    }

    @Test
    void firstAttemptInsideToleranceClosesImmediately() {
        var session = openSession();

        var attempt = session.registerAttempt(
                UUID.randomUUID(), Instant.parse("2026-06-25T11:00:00Z"),
                new BigDecimal("99.51"), new BigDecimal("100.00"), new BigDecimal("0.50"));

        assertThat(session.getStatus()).isEqualTo(CashSessionStatus.CERRADA);
        assertThat(session.getDiscrepancy()).isEqualByComparingTo("-0.49");
        assertThat(attempt.closedSession()).isTrue();
        assertThat(session.getAttempts()).hasSize(1);
    }

    @Test
    void firstAttemptOutsideToleranceLeavesSessionOpen() {
        var session = openSession();

        var attempt = session.registerAttempt(
                UUID.randomUUID(), Instant.parse("2026-06-25T12:00:00Z"),
                new BigDecimal("98.99"), new BigDecimal("100.00"), new BigDecimal("1.00"));

        assertThat(session.getStatus()).isEqualTo(CashSessionStatus.ABIERTA);
        assertThat(session.getClosedAt()).isNull();
        assertThat(session.getDiscrepancy()).isNull();
        assertThat(attempt.closedSession()).isFalse();
        assertThat(session.getAttempts()).hasSize(1);
    }

    @Test
    void denominationsAreInExpectedEuroOrder() {
        assertThat(CashDenomination.valuesInEuroOrder()).containsExactlyElementsOf(List.of(
                new BigDecimal("100.00"),
                new BigDecimal("50.00"),
                new BigDecimal("20.00"),
                new BigDecimal("10.00"),
                new BigDecimal("5.00"),
                new BigDecimal("2.00"),
                new BigDecimal("1.00"),
                new BigDecimal("0.50"),
                new BigDecimal("0.20"),
                new BigDecimal("0.10"),
                new BigDecimal("0.05"),
                new BigDecimal("0.02"),
                new BigDecimal("0.01")));
    }

    @Test
    void registerAttemptRejectsNegativeDeclaredFund() {
        var session = openSession();

        assertThatThrownBy(() -> session.registerAttempt(
                UUID.randomUUID(), Instant.parse("2026-06-25T13:00:00Z"),
                new BigDecimal("-0.01"), new BigDecimal("100.00"), new BigDecimal("1.00")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("declaredFund");
        assertThat(session.getStatus()).isEqualTo(CashSessionStatus.ABIERTA);
        assertThat(session.getAttempts()).isEmpty();
    }

    @Test
    void closeRejectsNegativeRetainedFund() {
        var session = openSession();

        assertThatThrownBy(() -> session.close(
                UUID.randomUUID(), Instant.parse("2026-06-25T13:30:00Z"),
                new BigDecimal("100.00"), new BigDecimal("-0.01"), BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("retainedFund");
        assertThat(session.getStatus()).isEqualTo(CashSessionStatus.ABIERTA);
    }

    @Test
    void movementDenominationRejectsZeroOrNegativeDenomination() {
        var movement = CashMovement.betweenSessions(
                UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("10.00"),
                Instant.parse("2026-06-25T14:00:00Z"), UUID.randomUUID(), null, null);

        assertThatThrownBy(() -> movement.addDenomination(BigDecimal.ZERO, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("denominacion");
        assertThatThrownBy(() -> movement.addDenomination(new BigDecimal("-0.01"), 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("denominacion");
    }

    @Test
    void sessionMovementDerivesStoreAndTerminalFromSession() {
        var session = openSession();

        var movement = CashMovement.sessionMovement(
                UUID.randomUUID(), UUID.randomUUID(), session, CashMovementType.ENTRADA,
                new BigDecimal("10.00"), Instant.parse("2026-06-25T14:30:00Z"),
                UUID.randomUUID(), null, null, null, null);

        assertThat(movement.getStoreId()).isEqualTo(session.getStoreId());
        assertThat(movement.getTerminalId()).isEqualTo(session.getTerminalId());
        assertThat(movement.getSessionId()).isEqualTo(session.getId());
    }

    private CashSession openSession() {
        return CashSession.open(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                Instant.parse("2026-06-25T09:00:00Z"),
                new BigDecimal("50.004"));
    }
}
