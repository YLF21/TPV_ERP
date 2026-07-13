package com.tpverp.backend.terminal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaymentTerminalAdjustmentServiceTest {
    private final Instant now = Instant.parse("2026-07-11T12:00:00Z");
    private final UUID terminal = UUID.randomUUID();
    private final UUID store = UUID.randomUUID();

    @Test
    void reservesRefundOnlyAgainstLockedCompatibleApprovedCharge() {
        var repository = mock(PaymentTerminalOperationRepository.class);
        var charge = approvedCharge();
        when(repository.findLockedById(charge.getId())).thenReturn(Optional.of(charge));
        when(repository.saveAndFlush(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> invocation.getArgument(0));
        var service = new PaymentTerminalAdjustmentService(repository);

        var refund = service.reserveRefund(UUID.randomUUID(), charge.getId(), terminal, store,
                PaymentTerminalProvider.REDSYS_TPV_PC, "refund-1", "c".repeat(64),
                new BigDecimal("4.00"), "d".repeat(64), 2, now);

        assertThat(refund.getOperationType()).isEqualTo(PaymentTerminalOperationType.REFUND);
        assertThat(refund.getOriginalOperationId()).isEqualTo(charge.getId());
        verify(repository).findLockedById(charge.getId());
    }

    @Test
    void reservesVoidForFullCompatibleChargeAndRejectsPartiallyRefundedCharge() {
        var repository = mock(PaymentTerminalOperationRepository.class);
        var charge = approvedCharge();
        when(repository.findLockedById(charge.getId())).thenReturn(Optional.of(charge));
        when(repository.saveAndFlush(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> invocation.getArgument(0));
        var service = new PaymentTerminalAdjustmentService(repository);
        var operation = service.reserveVoid(UUID.randomUUID(), charge.getId(), terminal, store,
                PaymentTerminalProvider.REDSYS_TPV_PC, "void-1", "c".repeat(64), "d".repeat(64), 2, now);
        assertThat(operation.getOperationType()).isEqualTo(PaymentTerminalOperationType.VOID);
        assertThat(operation.getAmount()).isEqualByComparingTo(charge.getAmount());

        charge.recordRefund(BigDecimal.ONE, now.plusSeconds(3));
        assertThatThrownBy(() -> service.reserveVoid(UUID.randomUUID(), charge.getId(), terminal, store,
                PaymentTerminalProvider.REDSYS_TPV_PC, "void-2", "c".repeat(64), "d".repeat(64), 2, now))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsVoidAfterChargeHasBeenCapturedIntoACommercialDocument() {
        var repository=mock(PaymentTerminalOperationRepository.class);var charge=approvedCharge();
        charge.linkDocument(UUID.randomUUID(),UUID.randomUUID(),now.plusSeconds(4));
        when(repository.findLockedById(charge.getId())).thenReturn(Optional.of(charge));
        var service=new PaymentTerminalAdjustmentService(repository);
        assertThatThrownBy(()->service.reserveVoid(UUID.randomUUID(),charge.getId(),terminal,store,
                PaymentTerminalProvider.REDSYS_TPV_PC,"void-settled","c".repeat(64),"d".repeat(64),2,now.plusSeconds(5)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("liquidado");
    }

    @Test
    void rejectsCrossTerminalProviderStoreAndOverRefund() {
        var repository = mock(PaymentTerminalOperationRepository.class);
        var charge = approvedCharge();
        when(repository.findLockedById(charge.getId())).thenReturn(Optional.of(charge));
        var service = new PaymentTerminalAdjustmentService(repository);

        assertThatThrownBy(() -> service.reserveRefund(UUID.randomUUID(), charge.getId(), UUID.randomUUID(),
                store, PaymentTerminalProvider.REDSYS_TPV_PC, "r1", "c".repeat(64), BigDecimal.ONE,
                "d".repeat(64), 1, now)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.reserveRefund(UUID.randomUUID(), charge.getId(), terminal,
                UUID.randomUUID(), PaymentTerminalProvider.REDSYS_TPV_PC, "r2", "c".repeat(64),
                BigDecimal.ONE, "d".repeat(64), 1, now)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.reserveRefund(UUID.randomUUID(), charge.getId(), terminal,
                store, PaymentTerminalProvider.PAYTEF, "r3", "c".repeat(64), BigDecimal.ONE,
                "d".repeat(64), 1, now)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.reserveRefund(UUID.randomUUID(), charge.getId(), terminal,
                store, PaymentTerminalProvider.REDSYS_TPV_PC, "r4", "c".repeat(64),
                new BigDecimal("10.01"), "d".repeat(64), 1, now)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void lockedAggregatePreventsCumulativeRefundBeyondRemainingBalance() {
        var repository = mock(PaymentTerminalOperationRepository.class);
        var charge = approvedCharge();
        when(repository.findLockedById(charge.getId())).thenReturn(Optional.of(charge));
        when(repository.reservedRefundAmount(charge.getId())).thenReturn(new BigDecimal("8.00"));
        var service = new PaymentTerminalAdjustmentService(repository);

        assertThatThrownBy(() -> service.reserveRefund(UUID.randomUUID(), charge.getId(), terminal, store,
                PaymentTerminalProvider.REDSYS_TPV_PC, "refund-2", "c".repeat(64),
                new BigDecimal("2.01"), "d".repeat(64), 2, now.plusSeconds(3)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void replaysSameRefundWithoutReservingOrCallingGatewayTwiceAndRejectsChangedPayload() {
        var repository = mock(PaymentTerminalOperationRepository.class);
        var charge = approvedCharge();
        var existing = PaymentTerminalOperation.reserve(UUID.randomUUID(), terminal, store,
                PaymentTerminalProvider.REDSYS_TPV_PC, PaymentTerminalMode.SIMULATED,
                PaymentTerminalOperationType.REFUND, charge.getId(), "refund-replay", "c".repeat(64),
                new BigDecimal("2.00"), "d".repeat(64), 2, now);
        when(repository.findByTerminalIdAndIdempotencyKey(terminal, "refund-replay"))
                .thenReturn(Optional.of(existing));
        var service = new PaymentTerminalAdjustmentService(repository);

        assertThat(service.reserveRefund(UUID.randomUUID(), charge.getId(), terminal, store,
                PaymentTerminalProvider.REDSYS_TPV_PC, "refund-replay", "c".repeat(64),
                new BigDecimal("2.00"), "d".repeat(64), 2, now)).isSameAs(existing);
        assertThatThrownBy(() -> service.reserveRefund(UUID.randomUUID(), charge.getId(), terminal, store,
                PaymentTerminalProvider.REDSYS_TPV_PC, "refund-replay", "e".repeat(64),
                new BigDecimal("3.00"), "d".repeat(64), 2, now))
                .isInstanceOf(IllegalStateException.class);
    }

    private PaymentTerminalOperation approvedCharge() {
        var charge = PaymentTerminalOperation.reserve(UUID.randomUUID(), terminal, store,
                PaymentTerminalProvider.REDSYS_TPV_PC, PaymentTerminalMode.SIMULATED,
                PaymentTerminalOperationType.CHARGE, null, "sale", "a".repeat(64),
                new BigDecimal("10.00"), "b".repeat(64), 1, now);
        charge.markSent("ATTEMPT", now.plusSeconds(1));
        charge.approve("REF", "AUTH", now.plusSeconds(2));
        return charge;
    }
}
