package com.tpverp.backend.party.loyalty.central;

import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.party.MemberRepository;
import com.tpverp.backend.terminal.CurrentTerminal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/member-balance-reservations")
@PreAuthorize("hasRole('ADMIN') or hasAuthority('GESTION_VENTAS') or hasAuthority('VENTA')")
public class LocalMemberBalanceReservationController {

    private final LocalMemberBalanceReservationService service;
    private final MemberRepository members;
    private final CurrentOrganization organization;
    private final CurrentTerminal currentTerminal;

    public LocalMemberBalanceReservationController(
            LocalMemberBalanceReservationService service,
            MemberRepository members,
            CurrentOrganization organization,
            CurrentTerminal currentTerminal) {
        this.service = service;
        this.members = members;
        this.organization = organization;
        this.currentTerminal = currentTerminal;
    }

    @PostMapping
    public ReservationView reserve(@Valid @RequestBody ReserveRequest request, Authentication authentication) {
        var store = organization.currentStore();
        var member = members.findByCustomerIdAndCompanyId(request.customerId(), store.getEmpresa().getId())
                .filter(candidate -> candidate.isActive())
                .orElseThrow(() -> new IllegalArgumentException("message.member.not_found"));
        return ReservationView.from(service.reserve(
                store.getId(), currentTerminal.terminalId(authentication), member.getId(), request.saleId()));
    }

    @PostMapping("/{reservationId}/heartbeat")
    public ReservationView heartbeat(
            @PathVariable UUID reservationId,
            @Valid @RequestBody OwnerRequest request,
            Authentication authentication) {
        return ReservationView.from(service.heartbeat(
                reservationId,
                organization.currentStore().getId(),
                currentTerminal.terminalId(authentication),
                request.saleId()));
    }

    @PostMapping("/{reservationId}/release")
    public ResponseEntity<ReservationView> release(
            @PathVariable UUID reservationId,
            @Valid @RequestBody OwnerRequest request,
            Authentication authentication) {
        LocalMemberBalanceReservation reservation = service.release(
                reservationId,
                organization.currentStore().getId(),
                currentTerminal.terminalId(authentication),
                request.saleId());
        HttpStatus status = reservation.getStatus() == LocalMemberBalanceReservationStatus.RELEASE_PENDING
                ? HttpStatus.ACCEPTED
                : HttpStatus.OK;
        return ResponseEntity.status(status).body(ReservationView.from(reservation));
    }

    public record ReserveRequest(
            @NotNull UUID customerId,
            @NotBlank @Size(max = 120) String saleId) {
    }

    public record OwnerRequest(
            @NotBlank @Size(max = 120) String saleId) {
    }

    public record ReservationView(
            UUID id,
            UUID centralReservationId,
            UUID storeId,
            UUID terminalId,
            UUID memberId,
            String saleId,
            String status,
            BigDecimal reservedLoyaltyAmount,
            BigDecimal reservedReturnCreditAmount,
            BigDecimal reservedTotal,
            BigDecimal preparedLoyaltyAmount,
            BigDecimal preparedReturnCreditAmount,
            BigDecimal preparedAmount,
            UUID prepareOperationId,
            UUID ticketId,
            BigDecimal consumedLoyaltyAmount,
            BigDecimal consumedReturnCreditAmount,
            BigDecimal consumedTotal,
            BigDecimal accountLoyaltyBalance,
            BigDecimal accountReturnCreditBalance,
            BigDecimal accountBalance,
            Instant heartbeatAt,
            Instant leaseExpiresAt) {

        static ReservationView from(LocalMemberBalanceReservation value) {
            return new ReservationView(
                    value.getId(),
                    value.getCentralReservationId(),
                    value.getStoreId(),
                    value.getTerminalId(),
                    value.getMemberId(),
                    value.getSaleId(),
                    value.getStatus().name(),
                    value.getReservedLoyaltyAmount(),
                    value.getReservedReturnCreditAmount(),
                    value.getReservedTotal(),
                    value.getPreparedLoyaltyAmount(),
                    value.getPreparedReturnCreditAmount(),
                    value.getPreparedAmount(),
                    value.getPrepareOperationId(),
                    value.getTicketId(),
                    value.getConsumedLoyaltyAmount(),
                    value.getConsumedReturnCreditAmount(),
                    value.getConsumedTotal(),
                    value.getAccountLoyaltyBalance(),
                    value.getAccountReturnCreditBalance(),
                    value.getAccountBalance(),
                    value.getHeartbeatAt(),
                    value.getLeaseExpiresAt());
        }
    }
}
