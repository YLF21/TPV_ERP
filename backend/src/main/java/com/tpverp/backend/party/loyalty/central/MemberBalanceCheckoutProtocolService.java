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
        LocalMemberBalanceReservation reservation = owned(reservationId, storeId, terminalId, saleId);
        reservation.apply(coordinator.prepare(
                reservation.getCentralReservationId(),
                storeId,
                terminalId,
                saleId,
                operationId,
                loyaltyAmount,
                returnCreditAmount), clock.instant());
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
                    reservation.getPrepareOperationId()), clock.instant());
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
}
