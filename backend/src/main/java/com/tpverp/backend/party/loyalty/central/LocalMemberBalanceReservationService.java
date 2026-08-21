package com.tpverp.backend.party.loyalty.central;

import com.tpverp.backend.terminal.TerminalRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LocalMemberBalanceReservationService {

    private final LocalMemberBalanceReservationRepository reservations;
    private final TerminalRepository terminals;
    private final MemberBalanceReservationCoordinator coordinator;
    private final Clock clock;

    public LocalMemberBalanceReservationService(
            LocalMemberBalanceReservationRepository reservations,
            TerminalRepository terminals,
            MemberBalanceReservationCoordinator coordinator,
            Clock clock) {
        this.reservations = reservations;
        this.terminals = terminals;
        this.coordinator = coordinator;
        this.clock = clock;
    }

    public LocalMemberBalanceReservation reserve(
            UUID storeId,
            UUID terminalId,
            UUID memberId,
            String saleId) {
        requireTerminal(storeId, terminalId);
        Instant now = clock.instant();
        LocalMemberBalanceReservation previous = reservations
                .findFirstByStoreIdAndTerminalIdAndSaleIdOrderByCreatedAtDesc(storeId, terminalId, saleId)
                .orElse(null);
        if (previous != null && previous.leaseExpiredAt(now)) {
            previous.markExpired(now);
            reservations.save(previous);
        }
        if (previous != null && previous.isActive() && previous.getMemberId().equals(memberId)) {
            MemberBalanceCentralGateway.ReservationResponse central = coordinator.reserve(
                    storeId, terminalId, memberId, saleId);
            previous.apply(central, now);
            return reservations.save(previous);
        }
        if (previous != null && !previous.isClosed()) {
            LocalMemberBalanceReservation released = releaseInternal(previous, false);
            if (!released.isClosed()) {
                throw new MemberBalanceCentralException(
                        MemberBalanceCentralException.Kind.UNAVAILABLE,
                        "La reserva anterior queda pendiente de liberacion");
            }
        }

        MemberBalanceCentralGateway.ReservationResponse central = coordinator.reserve(
                storeId, terminalId, memberId, saleId);
        LocalMemberBalanceReservation reservation = LocalMemberBalanceReservation.create(
                storeId, terminalId, memberId, saleId, central, now);
        try {
            return reservations.saveAndFlush(reservation);
        } catch (DataIntegrityViolationException exception) {
            return reservations.findByCentralReservationId(central.reservationId()).orElseThrow(() -> exception);
        }
    }

    @Transactional
    public LocalMemberBalanceReservation heartbeat(
            UUID reservationId,
            UUID storeId,
            UUID terminalId,
            String saleId) {
        requireTerminal(storeId, terminalId);
        LocalMemberBalanceReservation reservation = ownedForUpdate(
                reservationId, storeId, terminalId, saleId);
        Instant now = clock.instant();
        if (reservation.leaseExpiredAt(now)) {
            reservation.markExpired(now);
            throw new MemberBalanceCentralException(
                    MemberBalanceCentralException.Kind.CONFLICT,
                    "La reserva local ha superado su lease");
        }
        if (!reservation.isActive()) {
            throw new IllegalStateException("La reserva de saldo socio ya no esta activa");
        }
        try {
            reservation.apply(coordinator.heartbeat(
                    reservation.getCentralReservationId(), storeId, terminalId, saleId), now);
            return reservation;
        } catch (MemberBalanceCentralException exception) {
            if (exception.getKind() == MemberBalanceCentralException.Kind.CONFLICT) {
                reservation.markExpired(now);
                return reservations.save(reservation);
            }
            throw exception;
        }
    }

    @Transactional
    public LocalMemberBalanceReservation release(
            UUID reservationId,
            UUID storeId,
            UUID terminalId,
            String saleId) {
        requireTerminal(storeId, terminalId);
        return releaseInternal(ownedForUpdate(reservationId, storeId, terminalId, saleId), true);
    }

    private LocalMemberBalanceReservation releaseInternal(
            LocalMemberBalanceReservation reservation,
            boolean tolerateUnavailable) {
        if (reservation.isClosed()) {
            return reservation;
        }
        Instant now = clock.instant();
        if (reservation.leaseExpiredAt(now)) {
            reservation.markExpired(now);
            return reservations.save(reservation);
        }
        try {
            reservation.apply(coordinator.release(
                    reservation.getCentralReservationId(),
                    reservation.getStoreId(),
                    reservation.getTerminalId(),
                    reservation.getSaleId()), now);
            return reservations.save(reservation);
        } catch (MemberBalanceCentralException exception) {
            if (exception.getKind() == MemberBalanceCentralException.Kind.UNAVAILABLE) {
                reservation.markReleasePending(now);
                LocalMemberBalanceReservation saved = reservations.save(reservation);
                if (tolerateUnavailable) {
                    return saved;
                }
            }
            throw exception;
        }
    }

    private LocalMemberBalanceReservation ownedForUpdate(
            UUID reservationId,
            UUID storeId,
            UUID terminalId,
            String saleId) {
        LocalMemberBalanceReservation reservation = reservations.findForUpdate(reservationId)
                .orElseThrow(() -> new NoSuchElementException("Reserva de saldo socio no encontrada"));
        if (!reservation.matches(storeId, terminalId, saleId)) {
            throw new IllegalStateException("La reserva no pertenece a esta venta y terminal");
        }
        return reservation;
    }

    private void requireTerminal(UUID storeId, UUID terminalId) {
        if (storeId == null || terminalId == null
                || terminals.findByIdAndTiendaId(terminalId, storeId).isEmpty()) {
            throw new IllegalArgumentException("El terminal no pertenece a la tienda indicada");
        }
    }
}
