package com.tpverp.backend.party.loyalty.central;

import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class MemberBalanceReservationCoordinator {

    private final MemberBalanceCentralContextResolver contexts;
    private final MemberBalanceCentralGateway gateway;

    public MemberBalanceReservationCoordinator(
            MemberBalanceCentralContextResolver contexts,
            MemberBalanceCentralGateway gateway) {
        this.contexts = contexts;
        this.gateway = gateway;
    }

    public MemberBalanceCentralGateway.ReservationResponse reserve(
            UUID localStoreId,
            UUID terminalId,
            UUID memberId,
            String saleId) {
        MemberBalanceCentralContextResolver.CentralContext context = contexts.resolve(localStoreId);
        return gateway.reserve(new MemberBalanceCentralGateway.ReserveRequest(
                context.companyId(),
                context.storeId(),
                requireMember(memberId),
                requireTerminal(terminalId),
                requireSale(saleId)));
    }

    public MemberBalanceCentralGateway.ReservationResponse heartbeat(
            UUID reservationId,
            UUID localStoreId,
            UUID terminalId,
            String saleId) {
        MemberBalanceCentralContextResolver.CentralContext context = contexts.resolve(localStoreId);
        return gateway.heartbeat(
                requireReservation(reservationId),
                owner(context, terminalId, saleId));
    }

    public MemberBalanceCentralGateway.ReservationResponse release(
            UUID reservationId,
            UUID localStoreId,
            UUID terminalId,
            String saleId) {
        MemberBalanceCentralContextResolver.CentralContext context = contexts.resolve(localStoreId);
        return gateway.release(
                requireReservation(reservationId),
                owner(context, terminalId, saleId));
    }

    public MemberBalanceCentralGateway.ReservationResponse prepare(
            UUID reservationId,
            UUID localStoreId,
            UUID terminalId,
            String saleId,
            UUID operationId,
            BigDecimal loyaltyAmount,
            BigDecimal returnCreditAmount) {
        MemberBalanceCentralContextResolver.CentralContext context = contexts.resolve(localStoreId);
        if (loyaltyAmount == null || returnCreditAmount == null) {
            throw new IllegalArgumentException("Los importes del monedero son obligatorios");
        }
        if (loyaltyAmount.signum() < 0 || returnCreditAmount.signum() < 0) {
            throw new IllegalArgumentException("Los importes del monedero no pueden ser negativos");
        }
        if (loyaltyAmount.signum() == 0 && returnCreditAmount.signum() == 0) {
            throw new IllegalArgumentException("Debe prepararse al menos un importe del monedero");
        }
        if (operationId == null) {
            throw new IllegalArgumentException("operationId es obligatorio");
        }
        return gateway.prepare(
                requireReservation(reservationId),
                new MemberBalanceCentralGateway.PrepareRequest(
                        context.companyId(),
                        context.storeId(),
                        requireTerminal(terminalId),
                        requireSale(saleId),
                        operationId,
                        loyaltyAmount,
                        returnCreditAmount));
    }

    public MemberBalanceCentralGateway.ReservationResponse finalizePrepared(
            UUID reservationId,
            UUID localStoreId,
            UUID terminalId,
            String saleId,
            UUID operationId) {
        return preparedOperation(
                reservationId, localStoreId, terminalId, saleId, operationId, true);
    }

    public MemberBalanceCentralGateway.ReservationResponse abortPrepared(
            UUID reservationId,
            UUID localStoreId,
            UUID terminalId,
            String saleId,
            UUID operationId) {
        return preparedOperation(
                reservationId, localStoreId, terminalId, saleId, operationId, false);
    }

    private MemberBalanceCentralGateway.ReservationResponse preparedOperation(
            UUID reservationId,
            UUID localStoreId,
            UUID terminalId,
            String saleId,
            UUID operationId,
            boolean finalize) {
        MemberBalanceCentralContextResolver.CentralContext context = contexts.resolve(localStoreId);
        if (operationId == null) {
            throw new IllegalArgumentException("operationId es obligatorio");
        }
        var request = new MemberBalanceCentralGateway.PreparedOwnerRequest(
                context.companyId(),
                context.storeId(),
                requireTerminal(terminalId),
                requireSale(saleId),
                operationId);
        return finalize
                ? gateway.finalizePrepared(requireReservation(reservationId), request)
                : gateway.abortPrepared(requireReservation(reservationId), request);
    }

    private MemberBalanceCentralGateway.ReservationOwnerRequest owner(
            MemberBalanceCentralContextResolver.CentralContext context,
            UUID terminalId,
            String saleId) {
        return new MemberBalanceCentralGateway.ReservationOwnerRequest(
                context.companyId(),
                context.storeId(),
                requireTerminal(terminalId),
                requireSale(saleId));
    }

    private UUID requireReservation(UUID reservationId) {
        if (reservationId == null) {
            throw new IllegalArgumentException("reservationId es obligatorio");
        }
        return reservationId;
    }

    private UUID requireMember(UUID memberId) {
        if (memberId == null) {
            throw new IllegalArgumentException("memberId es obligatorio");
        }
        return memberId;
    }

    private String requireTerminal(UUID terminalId) {
        if (terminalId == null) {
            throw new IllegalArgumentException("terminalId es obligatorio");
        }
        return terminalId.toString();
    }

    private String requireSale(String saleId) {
        if (saleId == null || saleId.isBlank() || saleId.length() > 120) {
            throw new IllegalArgumentException("saleId es obligatorio y admite hasta 120 caracteres");
        }
        return saleId.trim();
    }
}
