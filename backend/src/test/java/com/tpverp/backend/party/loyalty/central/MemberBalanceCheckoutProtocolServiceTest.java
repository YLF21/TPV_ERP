package com.tpverp.backend.party.loyalty.central;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

import com.tpverp.backend.party.MemberRepository;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MemberBalanceCheckoutProtocolServiceTest {

    @Test
    void retentionSnapshotOwnershipRequiresCentralReservationSaleAndMemberMatch() {
        var reservations = mock(LocalMemberBalanceReservationRepository.class);
        var coordinator = mock(MemberBalanceReservationCoordinator.class);
        var reservation = mock(LocalMemberBalanceReservation.class);
        UUID localId = UUID.randomUUID();
        UUID centralId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();
        when(reservations.findById(localId)).thenReturn(Optional.of(reservation));
        when(reservation.getCentralReservationId()).thenReturn(centralId);
        when(reservation.getSaleId()).thenReturn("sale-1");
        when(reservation.getMemberId()).thenReturn(memberId);
        var service = new MemberBalanceCheckoutProtocolService(
                reservations, coordinator, mock(MemberRepository.class), Clock.systemUTC());

        org.assertj.core.api.Assertions.assertThat(service.retentionSnapshotBelongsTo(
                localId, centralId, "sale-1", memberId)).isTrue();
        org.assertj.core.api.Assertions.assertThat(service.retentionSnapshotBelongsTo(
                localId, centralId, "sale-other", memberId)).isFalse();
        org.assertj.core.api.Assertions.assertThat(service.retentionSnapshotBelongsTo(
                localId, UUID.randomUUID(), "sale-1", memberId)).isFalse();
    }

    @Test
    void releasedReservationCannotBeBoundToAnAlreadyFinalizedTicket() {
        var reservations = mock(LocalMemberBalanceReservationRepository.class);
        var reservation = mock(LocalMemberBalanceReservation.class);
        UUID reservationId = UUID.randomUUID();
        when(reservations.findForUpdate(reservationId)).thenReturn(Optional.of(reservation));
        when(reservation.getStatus()).thenReturn(LocalMemberBalanceReservationStatus.RELEASED);
        var service = new MemberBalanceCheckoutProtocolService(
                reservations,
                mock(MemberBalanceReservationCoordinator.class),
                mock(MemberRepository.class),
                Clock.systemUTC());

        assertThatThrownBy(() -> service.markTicketCommitted(
                reservationId, UUID.randomUUID()))
                .isInstanceOf(MemberBalanceManualReconciliationRequiredException.class)
                .hasMessageContaining("RELEASED");
    }

    @Test
    void activeReleaseUnavailableBecomesPendingForLeaseRecovery() {
        var reservations = mock(LocalMemberBalanceReservationRepository.class);
        var coordinator = mock(MemberBalanceReservationCoordinator.class);
        var reservation = mock(LocalMemberBalanceReservation.class);
        UUID reservationId = UUID.randomUUID();
        when(reservations.findById(reservationId)).thenReturn(Optional.of(reservation));
        when(reservation.isClosed()).thenReturn(false);
        when(reservation.isActive()).thenReturn(true);
        when(reservation.getCentralReservationId()).thenReturn(UUID.randomUUID());
        when(reservation.getStoreId()).thenReturn(UUID.randomUUID());
        when(reservation.getTerminalId()).thenReturn(UUID.randomUUID());
        when(reservation.getSaleId()).thenReturn("sale");
        when(coordinator.release(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenThrow(new MemberBalanceCentralException(
                        MemberBalanceCentralException.Kind.UNAVAILABLE, "offline"));
        when(reservations.save(reservation)).thenReturn(reservation);
        var service = new MemberBalanceCheckoutProtocolService(
                reservations, coordinator, mock(MemberRepository.class), Clock.systemUTC());

        service.abortPrepared(reservationId);

        verify(reservation).markReleasePending(org.mockito.ArgumentMatchers.any());
        verify(reservations).save(reservation);
    }

    @Test
    void activeReleaseNotFoundClosesLocalOwnershipIdempotently() {
        var reservations = mock(LocalMemberBalanceReservationRepository.class);
        var coordinator = mock(MemberBalanceReservationCoordinator.class);
        var reservation = mock(LocalMemberBalanceReservation.class);
        UUID reservationId = UUID.randomUUID();
        when(reservations.findById(reservationId)).thenReturn(Optional.of(reservation));
        when(reservation.isClosed()).thenReturn(false);
        when(reservation.isActive()).thenReturn(true);
        when(reservation.getCentralReservationId()).thenReturn(UUID.randomUUID());
        when(reservation.getStoreId()).thenReturn(UUID.randomUUID());
        when(reservation.getTerminalId()).thenReturn(UUID.randomUUID());
        when(reservation.getSaleId()).thenReturn("sale-404");
        when(coordinator.release(any(), any(), any(), any()))
                .thenThrow(new MemberBalanceCentralException(
                        MemberBalanceCentralException.Kind.REJECTED, 404,
                        "Reserva de saldo del miembro no encontrada"));
        when(reservations.save(reservation)).thenReturn(reservation);
        var service = new MemberBalanceCheckoutProtocolService(
                reservations, coordinator, mock(MemberRepository.class), Clock.systemUTC());

        service.abortPrepared(reservationId);

        verify(reservation).markReleaseConfirmed(any());
        verify(reservations).save(reservation);
        verify(coordinator).release(any(), any(), any(), eq("sale-404"));
    }

    @Test
    void releasePendingRetriesCentralReleaseWithoutPrepareOperation() {
        var reservations = mock(LocalMemberBalanceReservationRepository.class);
        var coordinator = mock(MemberBalanceReservationCoordinator.class);
        var reservation = mock(LocalMemberBalanceReservation.class);
        UUID reservationId = UUID.randomUUID();
        UUID centralId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UUID terminalId = UUID.randomUUID();
        when(reservations.findById(reservationId)).thenReturn(Optional.of(reservation));
        when(reservation.isClosed()).thenReturn(false);
        when(reservation.isActive()).thenReturn(false);
        when(reservation.getStatus()).thenReturn(LocalMemberBalanceReservationStatus.RELEASE_PENDING);
        when(reservation.getCentralReservationId()).thenReturn(centralId);
        when(reservation.getStoreId()).thenReturn(storeId);
        when(reservation.getTerminalId()).thenReturn(terminalId);
        when(reservation.getSaleId()).thenReturn("sale-release-pending");
        when(coordinator.release(centralId, storeId, terminalId, "sale-release-pending"))
                .thenReturn(mock(MemberBalanceCentralGateway.ReservationResponse.class));
        when(reservations.save(reservation)).thenReturn(reservation);
        var service = new MemberBalanceCheckoutProtocolService(
                reservations, coordinator, mock(MemberRepository.class), Clock.systemUTC());

        service.abortPrepared(reservationId);

        verify(coordinator).release(centralId, storeId, terminalId, "sale-release-pending");
        verify(coordinator, never()).abortPrepared(any(), any(), any(), any(), any());
        verify(reservations).save(reservation);
    }

    @Test
    void preparedAbortNotFoundClosesLocalOwnershipIdempotently() {
        var reservations = mock(LocalMemberBalanceReservationRepository.class);
        var coordinator = mock(MemberBalanceReservationCoordinator.class);
        var reservation = mock(LocalMemberBalanceReservation.class);
        UUID reservationId = UUID.randomUUID();
        UUID centralId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UUID terminalId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();
        when(reservations.findById(reservationId)).thenReturn(Optional.of(reservation));
        when(reservation.isClosed()).thenReturn(false);
        when(reservation.isActive()).thenReturn(false);
        when(reservation.getTicketId()).thenReturn(null);
        when(reservation.getCentralReservationId()).thenReturn(centralId);
        when(reservation.getStoreId()).thenReturn(storeId);
        when(reservation.getTerminalId()).thenReturn(terminalId);
        when(reservation.getSaleId()).thenReturn("sale-prepared-404");
        when(reservation.getPrepareOperationId()).thenReturn(operationId);
        when(coordinator.abortPrepared(centralId, storeId, terminalId, "sale-prepared-404", operationId))
                .thenThrow(new MemberBalanceCentralException(
                        MemberBalanceCentralException.Kind.REJECTED, 404,
                        "Reserva de saldo del miembro no encontrada"));
        when(reservations.save(reservation)).thenReturn(reservation);
        var service = new MemberBalanceCheckoutProtocolService(
                reservations, coordinator, mock(MemberRepository.class), Clock.systemUTC());

        service.abortPrepared(reservationId);

        verify(reservation).markReleaseConfirmed(any());
        verify(reservations).save(reservation);
    }

    @Test
    void activeRetentionSnapshotDoesNotRequireFinalizationAfterLocalCommit() {
        var reservations = mock(LocalMemberBalanceReservationRepository.class);
        var reservation = mock(LocalMemberBalanceReservation.class);
        UUID reservationId = UUID.randomUUID();
        when(reservations.findById(reservationId)).thenReturn(Optional.of(reservation));
        when(reservation.getStatus()).thenReturn(LocalMemberBalanceReservationStatus.ACTIVE);
        when(reservation.getPreparedAmount()).thenReturn(java.math.BigDecimal.ZERO.setScale(2));
        when(reservation.getRetentionRevision()).thenReturn(1L);
        var service = new MemberBalanceCheckoutProtocolService(
                reservations, mock(MemberBalanceReservationCoordinator.class),
                mock(MemberRepository.class), Clock.systemUTC());

        assertThat(service.requiresCentralWalletFinalization(reservationId)).isFalse();
    }

    @Test
    void positiveWalletPreparationRejectsUnconfirmedReturnRetention() {
        var reservations = mock(LocalMemberBalanceReservationRepository.class);
        var reservation = mock(LocalMemberBalanceReservation.class);
        UUID reservationId = UUID.randomUUID();
        when(reservations.findById(reservationId)).thenReturn(Optional.of(reservation));
        when(reservation.matches(any(), any(), eq("sale"))).thenReturn(true);
        when(reservation.getRetentionPendingMissing()).thenReturn(new java.math.BigDecimal("1.00"));
        when(reservation.getRetentionSpentShortfall()).thenReturn(java.math.BigDecimal.ZERO.setScale(2));
        when(reservation.getRetentionRecoveredKnown()).thenReturn(java.math.BigDecimal.ZERO.setScale(2));
        when(reservation.getRetentionAttributedAmount()).thenReturn(new java.math.BigDecimal("1.00"));
        when(reservation.getRetentionHeldKnown()).thenReturn(java.math.BigDecimal.ZERO.setScale(2));
        var service = new MemberBalanceCheckoutProtocolService(
                reservations, mock(MemberBalanceReservationCoordinator.class),
                mock(MemberRepository.class), Clock.systemUTC());

        assertThatThrownBy(() -> service.prepare(
                reservationId, UUID.randomUUID(), UUID.randomUUID(), "sale", UUID.randomUUID(),
                new java.math.BigDecimal("0.50"), java.math.BigDecimal.ZERO.setScale(2)))
                .isInstanceOf(MemberBalanceCentralException.class)
                .hasMessageContaining("no esta confirmada");
    }

    @Test
    void prepareNotFoundRemainsAnErrorAndCannotUseReleaseShortcut() {
        var reservations = mock(LocalMemberBalanceReservationRepository.class);
        var coordinator = mock(MemberBalanceReservationCoordinator.class);
        var reservation = mock(LocalMemberBalanceReservation.class);
        UUID reservationId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UUID terminalId = UUID.randomUUID();
        UUID centralId = UUID.randomUUID();
        when(reservations.findById(reservationId)).thenReturn(Optional.of(reservation));
        when(reservation.matches(storeId, terminalId, "sale-prepare-404")).thenReturn(true);
        when(reservation.getCentralReservationId()).thenReturn(centralId);
        when(coordinator.prepare(eq(centralId), eq(storeId), eq(terminalId), eq("sale-prepare-404"),
                any(), eq(java.math.BigDecimal.ZERO), eq(java.math.BigDecimal.ZERO), any(Long.class), any()))
                .thenThrow(new MemberBalanceCentralException(
                        MemberBalanceCentralException.Kind.REJECTED, 404,
                        "Reserva de saldo del miembro no encontrada"));
        var service = new MemberBalanceCheckoutProtocolService(
                reservations, coordinator, mock(MemberRepository.class), Clock.systemUTC());

        assertThatThrownBy(() -> service.prepare(
                reservationId, storeId, terminalId, "sale-prepare-404", UUID.randomUUID(),
                java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO))
                .isInstanceOf(MemberBalanceCentralException.class)
                .satisfies(error -> assertThat(((MemberBalanceCentralException) error).getStatusCode())
                        .isEqualTo(404));
        verify(reservation, never()).markReleaseConfirmed(any());
    }

    @Test
    void finalizeNotFoundRemainsAnErrorAndCannotUseReleaseShortcut() {
        var reservations = mock(LocalMemberBalanceReservationRepository.class);
        var coordinator = mock(MemberBalanceReservationCoordinator.class);
        var reservation = mock(LocalMemberBalanceReservation.class);
        UUID reservationId = UUID.randomUUID();
        UUID centralId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UUID terminalId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();
        when(reservations.findById(reservationId)).thenReturn(Optional.of(reservation));
        when(reservation.isClosed()).thenReturn(false);
        when(reservation.getCentralReservationId()).thenReturn(centralId);
        when(reservation.getStoreId()).thenReturn(storeId);
        when(reservation.getTerminalId()).thenReturn(terminalId);
        when(reservation.getSaleId()).thenReturn("sale-finalize-404");
        when(reservation.getPrepareOperationId()).thenReturn(operationId);
        when(coordinator.finalizePrepared(centralId, storeId, terminalId, "sale-finalize-404", operationId, null))
                .thenThrow(new MemberBalanceCentralException(
                        MemberBalanceCentralException.Kind.REJECTED, 404,
                        "Reserva de saldo del miembro no encontrada"));
        var service = new MemberBalanceCheckoutProtocolService(
                reservations, coordinator, mock(MemberRepository.class), Clock.systemUTC());

        assertThatThrownBy(() -> service.finalizePrepared(reservationId))
                .isInstanceOf(MemberBalanceCentralException.class)
                .satisfies(error -> assertThat(((MemberBalanceCentralException) error).getStatusCode())
                        .isEqualTo(404));
        verify(reservation, never()).markReleaseConfirmed(any());
    }

    @Test
    void mixedReturnWalletPreparationRequiresAConfiguredRetentionRevision() {
        var reservations = mock(LocalMemberBalanceReservationRepository.class);
        var reservation = mock(LocalMemberBalanceReservation.class);
        UUID reservationId = UUID.randomUUID();
        when(reservations.findById(reservationId)).thenReturn(Optional.of(reservation));
        var service = new MemberBalanceCheckoutProtocolService(
                reservations, mock(MemberBalanceReservationCoordinator.class),
                mock(MemberRepository.class), Clock.systemUTC());

        when(reservation.getRetentionRevision()).thenReturn(0L);
        when(reservation.getRetentionFingerprint()).thenReturn("");
        assertThatThrownBy(() -> service.requireRetentionConfiguredForReturn(reservationId))
                .isInstanceOf(MemberBalanceCentralException.class)
                .hasMessage("member_balance_retention_requires_return");

        when(reservation.getRetentionRevision()).thenReturn(1L);
        when(reservation.getRetentionFingerprint()).thenReturn("fingerprint");
        assertThatCode(() -> service.requireRetentionConfiguredForReturn(reservationId))
                .doesNotThrowAnyException();
    }
}
