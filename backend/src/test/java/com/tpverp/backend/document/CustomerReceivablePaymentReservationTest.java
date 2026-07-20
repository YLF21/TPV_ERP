package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;

import com.tpverp.backend.terminal.PaymentTerminalOperationStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CustomerReceivablePaymentReservationTest {

    private static final Instant NOW = Instant.parse("2026-07-16T10:00:00Z");

    @Test
    void cardReservationRemainsAuthoritativeAcrossUncertainResultAndCanBeReclaimed() {
        var owner = UUID.randomUUID();
        var nextOwner = UUID.randomUUID();
        var reservation = CustomerReceivablePaymentReservation.reserve(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "a".repeat(64), new BigDecimal("60.00"),
                CustomerReceivablePaymentReservation.Kind.INTEGRATED_CARD,
                owner, NOW.plusSeconds(30), NOW);

        reservation.markDispatching(owner, NOW.plusSeconds(1));
        reservation.recordTerminalResult(PaymentTerminalOperationStatus.TIMEOUT,
                NOW.plusSeconds(2));

        assertThat(reservation.reservesBalance()).isTrue();
        assertThat(reservation.claim(nextOwner, NOW.plusSeconds(61), NOW.plusSeconds(31))).isTrue();
        assertThat(reservation.isOwnedBy(nextOwner)).isTrue();
    }

    @Test
    void definitiveDeclineReleasesBalanceWhileApprovalKeepsItUntilDocumentPaymentCompletes() {
        var declined = reservation();
        declined.markDispatching(declined.getLeaseOwner(), NOW.plusSeconds(1));
        declined.recordTerminalResult(PaymentTerminalOperationStatus.DECLINED, NOW.plusSeconds(2));
        assertThat(declined.reservesBalance()).isFalse();

        var cancelled = reservation();
        cancelled.markDispatching(cancelled.getLeaseOwner(), NOW.plusSeconds(1));
        cancelled.recordTerminalResult(PaymentTerminalOperationStatus.CANCELLED, NOW.plusSeconds(2));
        assertThat(cancelled.reservesBalance()).isFalse();

        var approved = reservation();
        approved.markDispatching(approved.getLeaseOwner(), NOW.plusSeconds(1));
        approved.recordTerminalResult(PaymentTerminalOperationStatus.APPROVED, NOW.plusSeconds(2));
        assertThat(approved.reservesBalance()).isTrue();
        approved.complete(UUID.randomUUID(), NOW.plusSeconds(3));
        assertThat(approved.reservesBalance()).isFalse();
    }

    @Test
    void finalErrorReleasesBalanceButUncertainErrorKeepsTheSamePaymentIdReserved() {
        var uncertain = reservation();
        uncertain.markDispatching(uncertain.getLeaseOwner(), NOW.plusSeconds(1));
        uncertain.recordTerminalResult(
                PaymentTerminalOperationStatus.ERROR, false, NOW.plusSeconds(2));
        assertThat(uncertain.getStatus())
                .isEqualTo(CustomerReceivablePaymentReservation.Status.DISPATCHING);
        assertThat(uncertain.reservesBalance()).isTrue();

        var finalError = reservation();
        finalError.markDispatching(finalError.getLeaseOwner(), NOW.plusSeconds(1));
        finalError.recordTerminalResult(
                PaymentTerminalOperationStatus.ERROR, true, NOW.plusSeconds(2));
        assertThat(finalError.getStatus())
                .isEqualTo(CustomerReceivablePaymentReservation.Status.RELEASED);
        assertThat(finalError.reservesBalance()).isFalse();
    }

    @Test
    void releasedPreDispatchReservationCanBeReactivatedWithSameIdentity() {
        var reservation = reservation();
        reservation.release(NOW.plusSeconds(31));
        var nextOwner = UUID.randomUUID();

        reservation.reactivate(nextOwner, NOW.plusSeconds(62), NOW.plusSeconds(32));

        assertThat(reservation.getStatus())
                .isEqualTo(CustomerReceivablePaymentReservation.Status.RESERVED);
        assertThat(reservation.isOwnedBy(nextOwner)).isTrue();
        assertThat(reservation.reservesBalance()).isTrue();
    }

    private CustomerReceivablePaymentReservation reservation() {
        return CustomerReceivablePaymentReservation.reserve(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "b".repeat(64), new BigDecimal("60.00"),
                CustomerReceivablePaymentReservation.Kind.INTEGRATED_CARD,
                UUID.randomUUID(), NOW.plusSeconds(30), NOW);
    }
}
