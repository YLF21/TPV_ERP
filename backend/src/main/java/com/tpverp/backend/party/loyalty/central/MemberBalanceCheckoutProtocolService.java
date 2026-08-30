package com.tpverp.backend.party.loyalty.central;

import com.tpverp.backend.party.MemberRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MemberBalanceCheckoutProtocolService {

    private final LocalMemberBalanceReservationRepository reservations;
    private final MemberBalanceReservationCoordinator coordinator;
    private final MemberRepository members;
    private final Clock clock;

    public MemberBalanceCheckoutProtocolService(
            LocalMemberBalanceReservationRepository reservations,
            MemberBalanceReservationCoordinator coordinator,
            MemberRepository members,
            Clock clock) {
        this.reservations = reservations;
        this.coordinator = coordinator;
        this.members = members;
        this.clock = clock;
    }

    public LocalMemberBalanceReservation prepare(
            UUID reservationId,
            UUID storeId,
            UUID terminalId,
            String saleId,
            UUID operationId,
            BigDecimal loyaltyAmount,
            BigDecimal returnCreditAmount) {
        LocalMemberBalanceReservation reservation = owned(
                reservationId, storeId, terminalId, saleId);
        return prepare(reservationId, storeId, terminalId, saleId, operationId,
                loyaltyAmount, returnCreditAmount,
                reservation.getRetentionRevision(), reservation.getRetentionFingerprint());
    }

    public LocalMemberBalanceReservation prepare(
            UUID reservationId,
            UUID storeId,
            UUID terminalId,
            String saleId,
            UUID operationId,
            BigDecimal loyaltyAmount,
            BigDecimal returnCreditAmount,
            long expectedRetentionRevision,
            String expectedRetentionFingerprint) {
        LocalMemberBalanceReservation reservation = owned(reservationId, storeId, terminalId, saleId);
        BigDecimal requestedLoyalty = loyaltyAmount == null
                ? BigDecimal.ZERO : loyaltyAmount;
        BigDecimal requestedReturnCredit = returnCreditAmount == null
                ? BigDecimal.ZERO : returnCreditAmount;
        if (requestedLoyalty.add(requestedReturnCredit).signum() > 0
                && retentionIsNotConfirmed(reservation)) {
            throw new MemberBalanceCentralException(
                    MemberBalanceCentralException.Kind.CONFLICT,
                    "La retencion de devolucion aun no esta confirmada");
        }
        reservation.apply(coordinator.prepare(
                reservation.getCentralReservationId(),
                storeId,
                terminalId,
                saleId,
                operationId,
                loyaltyAmount,
                returnCreditAmount,
                expectedRetentionRevision,
                expectedRetentionFingerprint), clock.instant());
        return reservations.save(reservation);
    }

    private static boolean retentionIsNotConfirmed(LocalMemberBalanceReservation reservation) {
        return reservation.getRetentionPendingMissing().signum() > 0
                || reservation.getRetentionSpentShortfall().signum() > 0
                || reservation.getRetentionRecoveredKnown().signum() > 0
                || reservation.getRetentionAttributedAmount().compareTo(
                        reservation.getRetentionHeldKnown()) != 0;
    }

    /** A mixed return cannot consume F10 until its retention snapshot exists. */
    public void requireRetentionConfiguredForReturn(UUID reservationId) {
        LocalMemberBalanceReservation reservation = required(reservationId);
        if (reservation.getRetentionRevision() <= 0
                || reservation.getRetentionFingerprint() == null
                || reservation.getRetentionFingerprint().isBlank()) {
            throw new MemberBalanceCentralException(
                    MemberBalanceCentralException.Kind.CONFLICT,
                    "member_balance_retention_requires_return");
        }
    }

    @Transactional
    public LocalMemberBalanceReservation configureRetention(
            UUID reservationId,
            UUID storeId,
            UUID terminalId,
            String saleId,
            UUID operationId,
            UUID sourceDocumentId,
            BigDecimal attributedAmount,
            java.util.List<MemberBalanceCentralGateway.RetentionClaim> claims) {
        LocalMemberBalanceReservation reservation = owned(
                reservationId, storeId, terminalId, saleId);
        reservation.apply(coordinator.configureRetention(
                reservation.getCentralReservationId(), storeId, terminalId, saleId,
                operationId, sourceDocumentId, attributedAmount, claims), clock.instant());
        return reservations.save(reservation);
    }

    @Transactional
    public void authorizePreparedLocalConsumption(
            UUID reservationId,
            UUID customerId,
            BigDecimal loyaltyAmount,
            BigDecimal returnCreditAmount) {
        LocalMemberBalanceReservation reservation = reservations.findForUpdate(reservationId)
                .orElseThrow(() -> new NoSuchElementException("Reserva de saldo socio no encontrada"));
        if (reservation.getStatus() != LocalMemberBalanceReservationStatus.PREPARED) {
            throw new IllegalStateException("La reserva no esta preparada para el cobro local");
        }
        if (loyaltyAmount == null
                || returnCreditAmount == null
                || reservation.getPreparedLoyaltyAmount().compareTo(loyaltyAmount) != 0
                || reservation.getPreparedReturnCreditAmount().compareTo(returnCreditAmount) != 0) {
            throw new IllegalStateException("Los importes no coinciden con la reserva preparada");
        }
        var member = members.findById(reservation.getMemberId())
                .orElseThrow(() -> new NoSuchElementException("Socio de la reserva no encontrado"));
        if (!member.getCustomer().getId().equals(customerId)) {
            throw new IllegalStateException("La reserva no pertenece al cliente de la venta");
        }
        member.refreshOfficialWallet(
                reservation.getAccountLoyaltyBalance(),
                reservation.getAccountReturnCreditBalance(),
                clock.instant());
    }

    @Transactional
    public void markTicketCommitted(UUID reservationId, UUID ticketId) {
        LocalMemberBalanceReservation reservation = reservations.findForUpdate(reservationId)
                .orElseThrow(() -> new NoSuchElementException("Reserva de saldo socio no encontrada"));
        if (reservation.getStatus() != LocalMemberBalanceReservationStatus.PREPARED
                && reservation.getStatus() != LocalMemberBalanceReservationStatus.TICKET_COMMITTED
                && reservation.getStatus() != LocalMemberBalanceReservationStatus.FINALIZE_PENDING
                && reservation.getStatus() != LocalMemberBalanceReservationStatus.CONSUMED) {
            throw new MemberBalanceManualReconciliationRequiredException(
                    reservationId, reservation.getStatus());
        }
        reservation.markTicketCommitted(ticketId, clock.instant());
    }

    public LocalMemberBalanceReservation finalizePrepared(UUID reservationId) {
        return finalizePrepared(reservationId, null);
    }

    public LocalMemberBalanceReservation finalizePrepared(
            UUID reservationId,
            MemberBalanceCentralGateway.RetentionSnapshot retentionSnapshot) {
        LocalMemberBalanceReservation reservation = required(reservationId);
        if (reservation.getStatus() == LocalMemberBalanceReservationStatus.CONSUMED) {
            return reservation;
        }
        try {
            reservation.apply(coordinator.finalizePrepared(
                    reservation.getCentralReservationId(),
                    reservation.getStoreId(),
                    reservation.getTerminalId(),
                    reservation.getSaleId(),
                    reservation.getPrepareOperationId(),
                    retentionSnapshot), clock.instant());
        } catch (MemberBalanceCentralException exception) {
            if (exception.getKind() == MemberBalanceCentralException.Kind.UNAVAILABLE) {
                reservation.markFinalizePending(clock.instant());
                return reservations.save(reservation);
            }
            throw exception;
        }
        return reservations.save(reservation);
    }

    public LocalMemberBalanceReservation abortPrepared(UUID reservationId) {
        LocalMemberBalanceReservation reservation = required(reservationId);
        if (reservation.isClosed()) {
            return reservation;
        }
        // RELEASE_PENDING is a retryable release protocol state.  Retry the
        // idempotent central release directly; it has no prepare operation
        // and must never fall through to abortPrepared with a null operation.
        if (reservation.isActive()
                || reservation.getStatus() == LocalMemberBalanceReservationStatus.RELEASE_PENDING) {
            try {
                reservation.apply(coordinator.release(
                        reservation.getCentralReservationId(), reservation.getStoreId(),
                        reservation.getTerminalId(), reservation.getSaleId()), clock.instant());
            } catch (MemberBalanceCentralException exception) {
                if (exception.getStatusCode() != null && exception.getStatusCode() == 404) {
                    reservation.markReleaseConfirmed(clock.instant());
                    return reservations.save(reservation);
                }
                if (exception.getKind() == MemberBalanceCentralException.Kind.UNAVAILABLE) {
                    reservation.markReleasePending(clock.instant());
                    return reservations.save(reservation);
                }
                throw exception;
            }
            return reservations.save(reservation);
        }
        if (reservation.getTicketId() != null) {
            throw new IllegalStateException("No se puede abortar saldo despues de confirmar el ticket");
        }
        try {
            reservation.apply(coordinator.abortPrepared(
                    reservation.getCentralReservationId(),
                    reservation.getStoreId(),
                    reservation.getTerminalId(),
                    reservation.getSaleId(),
                    reservation.getPrepareOperationId()), clock.instant());
        } catch (MemberBalanceCentralException exception) {
            if (exception.getStatusCode() != null && exception.getStatusCode() == 404) {
                reservation.markReleaseConfirmed(clock.instant());
                return reservations.save(reservation);
            }
            if (exception.getKind() == MemberBalanceCentralException.Kind.UNAVAILABLE) {
                reservation.markAbortPending(clock.instant());
                return reservations.save(reservation);
            }
            throw exception;
        }
        return reservations.save(reservation);
    }

    private LocalMemberBalanceReservation owned(
            UUID reservationId,
            UUID storeId,
            UUID terminalId,
            String saleId) {
        LocalMemberBalanceReservation reservation = required(reservationId);
        if (!reservation.matches(storeId, terminalId, saleId)) {
            throw new IllegalStateException("La reserva no pertenece a esta venta y terminal");
        }
        return reservation;
    }

    private LocalMemberBalanceReservation required(UUID reservationId) {
        return reservations.findById(reservationId)
                .orElseThrow(() -> new NoSuchElementException("Reserva de saldo socio no encontrada"));
    }

    public boolean requiresCentralWalletCompletion(UUID reservationId) {
        return reservations.findById(reservationId)
                .filter(value -> value.getStatus() == LocalMemberBalanceReservationStatus.ACTIVE
                        || value.getStatus() == LocalMemberBalanceReservationStatus.PREPARED
                        || value.getStatus() == LocalMemberBalanceReservationStatus.TICKET_COMMITTED
                        || value.getStatus() == LocalMemberBalanceReservationStatus.FINALIZE_PENDING
                        || value.getStatus() == LocalMemberBalanceReservationStatus.ABORT_PENDING)
                .map(value -> value.getPreparedAmount().signum() > 0
                        || value.getRetentionRevision() > 0)
                .orElse(false);
    }

    /**
     * Keeps a recovery snapshot from one member/session from being attached to
     * another prepared reservation. Standalone recovery events are intentionally
     * not eligible for checkout finalization.
     */
    public boolean retentionSnapshotBelongsTo(
            UUID reservationId, UUID centralReservationId, String saleId, UUID memberId) {
        if (reservationId == null || centralReservationId == null || saleId == null || memberId == null) {
            return false;
        }
        return reservations.findById(reservationId)
                .filter(value -> centralReservationId.equals(value.getCentralReservationId()))
                .filter(value -> saleId.equals(value.getSaleId()))
                .filter(value -> memberId.equals(value.getMemberId()))
                .isPresent();
    }

    public boolean hasEffectiveRetention(UUID reservationId) {
        return reservations.findById(reservationId)
                .filter(value -> value.getRetentionRevision() > 0)
                .map(value -> value.getRetentionAttributedAmount().signum() > 0
                        || value.getRetentionHeldKnown().signum() > 0
                        || value.getRetentionPendingMissing().signum() > 0)
                .orElse(false);
    }

    public boolean requiresCentralWalletFinalization(UUID reservationId) {
        return reservations.findById(reservationId)
                .filter(value -> value.getStatus() != LocalMemberBalanceReservationStatus.ACTIVE)
                .map(value -> value.getPreparedAmount().signum() > 0
                        || value.getRetentionRevision() > 0)
                .orElse(false);
    }
}
