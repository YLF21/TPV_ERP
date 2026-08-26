package com.tpverp.backend.party.loyalty.central;

import com.tpverp.backend.party.MemberBalanceLot;
import com.tpverp.backend.party.MemberBalanceLotRepository;
import com.tpverp.backend.party.MemberBalanceLotType;
import com.tpverp.backend.party.MemberRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Profile("dev")
@ConditionalOnExpression("'${tpv.sync.central-url:}' == ''")
public class DevMemberBalanceCentralGateway implements MemberBalanceCentralGateway {

    private static final int HEARTBEAT_INTERVAL_SECONDS = 30;
    private static final int LEASE_SECONDS = 120;
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);
    private static final UUID INSTALLATION_ID = UUID.nameUUIDFromBytes(
            "tpv-erp-dev-member-balance-simulator".getBytes(StandardCharsets.UTF_8));

    private final MemberBalanceLotRepository lots;
    private final MemberRepository members;
    private final LocalMemberBalanceReservationRepository localReservations;
    private final Clock clock;
    private final Map<UUID, DevReservation> reservations = new HashMap<>();

    public DevMemberBalanceCentralGateway(
            MemberBalanceLotRepository lots,
            MemberRepository members,
            LocalMemberBalanceReservationRepository localReservations,
            Clock clock) {
        this.lots = lots;
        this.members = members;
        this.localReservations = localReservations;
        this.clock = clock;
    }

    @Override
    public synchronized BootstrapResponse bootstrap(BootstrapRequest request) {
        return new BootstrapResponse(
                "READY",
                request.storeId(),
                INSTALLATION_ID,
                request.checksum(),
                request.snapshotAt(),
                request.accounts().size());
    }

    @Override
    public Optional<MemberWalletBootstrapStatus> discoverBootstrap(
            BootstrapStoreRequest request) {
        return Optional.empty();
    }

    @Override
    public void beginBootstrapSnapshot(
            UUID bootstrapId,
            BootstrapSnapshotBeginRequest request) {
        // Development without SaaS has no historical bootstrap to upload.
    }

    @Override
    public void uploadBootstrapChunk(
            UUID bootstrapId,
            UUID snapshotId,
            BootstrapChunkKind kind,
            int index,
            BootstrapSnapshotChunkRequest request) {
        request.validateFor(kind);
    }

    @Override
    public void completeBootstrapSnapshot(
            UUID bootstrapId,
            UUID snapshotId,
            BootstrapSnapshotCompleteRequest request) {
        // Development without SaaS treats the bootstrap as already complete.
    }

    @Override
    public MemberWalletBootstrapStatus bootstrapStatus(
            UUID bootstrapId,
            BootstrapStoreRequest request) {
        Instant now = clock.instant();
        return new MemberWalletBootstrapStatus(
                bootstrapId,
                request.companyId(),
                MemberWalletBootstrapStatusValue.COMPLETED,
                now,
                List.of(request.storeId()),
                List.of(request.storeId()),
                List.of(),
                List.of(),
                null,
                now,
                now);
    }

    @Override
    @Transactional
    public synchronized ReservationResponse reserve(ReserveRequest request) {
        Instant now = clock.instant();
        expireActiveLeases(now);
        for (DevReservation current : reservations.values()) {
            if (!current.memberId.equals(request.memberId()) || current.closed()) {
                continue;
            }
            if (current.matches(request)) {
                refreshOfficialLoyaltyBalance(
                        current.memberId, current.accountLoyaltyBalance, now);
                return current.response();
            }
            throw conflict("El saldo del socio ya esta reservado en otra caja o venta");
        }

        AvailableWallet wallet = availableWallet(request.memberId(), now);
        refreshOfficialLoyaltyBalance(request.memberId(), wallet.loyaltyBalance(), now);
        DevReservation created = DevReservation.active(UUID.randomUUID(), request, wallet, now);
        reservations.put(created.id, created);
        return created.response();
    }

    @Override
    @Transactional
    public synchronized ReservationResponse heartbeat(
            UUID reservationId,
            ReservationOwnerRequest request) {
        Instant now = clock.instant();
        DevReservation reservation = owned(reservationId, request);
        reservation.expireIfNecessary(now);
        if (!"ACTIVE".equals(reservation.status)) {
            throw rejected("La reserva de saldo no admite latidos");
        }
        reservation.heartbeatAt = now;
        reservation.leaseExpiresAt = now.plusSeconds(LEASE_SECONDS);
        refreshOfficialLoyaltyBalance(
                reservation.memberId, reservation.accountLoyaltyBalance, now);
        return reservation.response();
    }

    @Override
    @Transactional
    public synchronized ReservationResponse release(
            UUID reservationId,
            ReservationOwnerRequest request) {
        DevReservation reservation = owned(reservationId, request);
        if (!"CONSUMED".equals(reservation.status)) {
            reservation.status = "RELEASED";
            reservation.preparedLoyaltyAmount = ZERO;
            reservation.preparedReturnCreditAmount = ZERO;
            reservation.prepareOperationId = null;
        }
        return reservation.response();
    }

    @Override
    @Transactional
    public synchronized ReservationResponse prepare(UUID reservationId, PrepareRequest request) {
        Instant now = clock.instant();
        DevReservation reservation = owned(reservationId, request);
        reservation.expireIfNecessary(now);
        BigDecimal loyaltyAmount = money(request.loyaltyAmount());
        BigDecimal returnCreditAmount = money(request.returnCreditAmount());
        reservation.validatePreparedAmounts(loyaltyAmount, returnCreditAmount);
        if ("PREPARED".equals(reservation.status)
                && request.operationId().equals(reservation.prepareOperationId)) {
            reservation.preparedLoyaltyAmount = loyaltyAmount;
            reservation.preparedReturnCreditAmount = returnCreditAmount;
            return reservation.response();
        }
        if (!"ACTIVE".equals(reservation.status)) {
            throw rejected("La reserva de saldo no se puede preparar");
        }
        reservation.status = "PREPARED";
        reservation.preparedLoyaltyAmount = loyaltyAmount;
        reservation.preparedReturnCreditAmount = returnCreditAmount;
        reservation.prepareOperationId = request.operationId();
        return reservation.response();
    }

    @Override
    @Transactional
    public synchronized ReservationResponse finalizePrepared(
            UUID reservationId,
            PreparedOwnerRequest request) {
        DevReservation reservation = owned(reservationId, request);
        if ("CONSUMED".equals(reservation.status)
                && request.operationId().equals(reservation.prepareOperationId)) {
            return reservation.response();
        }
        reservation.requirePrepared(request.operationId());
        reservation.status = "CONSUMED";
        reservation.consumePreparedLots();
        reservation.consumedLoyaltyAmount = reservation.preparedLoyaltyAmount;
        reservation.consumedReturnCreditAmount = reservation.preparedReturnCreditAmount;
        reservation.accountLoyaltyBalance = reservation.accountLoyaltyBalance
                .subtract(reservation.preparedLoyaltyAmount)
                .max(ZERO);
        reservation.accountReturnCreditBalance = reservation.accountReturnCreditBalance
                .subtract(reservation.preparedReturnCreditAmount)
                .max(ZERO);
        return reservation.response();
    }

    @Override
    @Transactional
    public synchronized ReservationResponse abortPrepared(
            UUID reservationId,
            PreparedOwnerRequest request) {
        DevReservation reservation = owned(reservationId, request);
        if ("ACTIVE".equals(reservation.status)) {
            return reservation.response();
        }
        reservation.requirePrepared(request.operationId());
        Instant now = clock.instant();
        reservation.status = "ACTIVE";
        reservation.preparedLoyaltyAmount = ZERO;
        reservation.preparedReturnCreditAmount = ZERO;
        reservation.prepareOperationId = null;
        reservation.heartbeatAt = now;
        reservation.leaseExpiresAt = now.plusSeconds(LEASE_SECONDS);
        return reservation.response();
    }

    private AvailableWallet availableWallet(UUID memberId, Instant now) {
        List<ReservedLot> reservedLots = lots
                .findByMemberIdAndAmountRemainingGreaterThan(memberId, ZERO).stream()
                .filter(lot -> lot.getExpiredAt() == null)
                .filter(lot -> lot.getExpiresAt() == null || lot.getExpiresAt().isAfter(now))
                .sorted(Comparator
                        .comparingInt((MemberBalanceLot lot) -> lot.getBalanceType()
                                == MemberBalanceLotType.LOYALTY ? 0 : 1)
                        .thenComparing(MemberBalanceLot::getCreatedAt)
                        .thenComparing(MemberBalanceLot::getId))
                .map(lot -> new ReservedLot(
                        lot.getBalanceType(),
                        lot.getId(),
                        money(lot.getAmountRemaining()),
                        lot.getCreatedAt(),
                        lot.getExpiresAt(),
                        lot.getSourceMovement() == null ? null : lot.getSourceMovement().getId(),
                        lot.getDocumentId()))
                .toList();
        BigDecimal loyaltyBalance = reservedLots.stream()
                .filter(lot -> lot.balanceType() == MemberBalanceLotType.LOYALTY)
                .map(ReservedLot::remainingAmount)
                .reduce(ZERO, BigDecimal::add);
        BigDecimal returnCreditBalance = reservedLots.stream()
                .filter(lot -> lot.balanceType() == MemberBalanceLotType.RETURN_CREDIT)
                .map(ReservedLot::remainingAmount)
                .reduce(ZERO, BigDecimal::add);
        return new AvailableWallet(
                money(loyaltyBalance),
                money(returnCreditBalance),
                reservedLots);
    }

    private void refreshOfficialLoyaltyBalance(UUID memberId, BigDecimal balance, Instant now) {
        var member = members.findById(memberId)
                .orElseThrow(() -> rejected("Socio no encontrado"));
        member.refreshOfficialBalance(balance, now);
    }

    private void expireActiveLeases(Instant now) {
        reservations.values().forEach(reservation -> reservation.expireIfNecessary(now));
    }

    private DevReservation owned(UUID reservationId, ReservationOwnerRequest request) {
        DevReservation reservation = required(reservationId);
        if (!reservation.matches(
                request.companyId(), request.storeId(), request.terminalId(), request.saleId())) {
            throw conflict("La reserva pertenece a otra caja o venta");
        }
        return reservation;
    }

    private DevReservation owned(UUID reservationId, PrepareRequest request) {
        DevReservation reservation = required(reservationId);
        if (!reservation.matches(
                request.companyId(), request.storeId(), request.terminalId(), request.saleId())) {
            throw conflict("La reserva pertenece a otra caja o venta");
        }
        return reservation;
    }

    private DevReservation owned(UUID reservationId, PreparedOwnerRequest request) {
        DevReservation reservation = required(reservationId);
        if (!reservation.matches(
                request.companyId(), request.storeId(), request.terminalId(), request.saleId())) {
            throw conflict("La reserva pertenece a otra caja o venta");
        }
        return reservation;
    }

    private DevReservation required(UUID reservationId) {
        DevReservation reservation = reservations.get(reservationId);
        if (reservation == null) {
            reservation = localReservations.findByCentralReservationId(reservationId)
                    .map(this::restore)
                    .orElseThrow(() -> rejected("Reserva de saldo no encontrada"));
            reservations.put(reservationId, reservation);
        }
        return reservation;
    }

    private DevReservation restore(LocalMemberBalanceReservation local) {
        var member = members.findById(local.getMemberId())
                .orElseThrow(() -> rejected("Socio no encontrado"));
        return DevReservation.restore(
                local,
                member.getCustomer().getCompany().getId());
    }

    private BigDecimal money(BigDecimal amount) {
        if (amount == null) {
            return ZERO;
        }
        return amount.setScale(2, RoundingMode.HALF_UP);
    }

    private MemberBalanceCentralException conflict(String message) {
        return new MemberBalanceCentralException(
                MemberBalanceCentralException.Kind.CONFLICT,
                message);
    }

    private MemberBalanceCentralException rejected(String message) {
        return new MemberBalanceCentralException(
                MemberBalanceCentralException.Kind.REJECTED,
                message);
    }

    private record AvailableWallet(
            BigDecimal loyaltyBalance,
            BigDecimal returnCreditBalance,
            List<ReservedLot> reservedLots) {
    }

    private static final class DevReservation {
        private final UUID id;
        private final UUID companyId;
        private final UUID storeId;
        private final UUID memberId;
        private final String terminalId;
        private final String saleId;
        private String status;
        private final BigDecimal reservedLoyaltyAmount;
        private final BigDecimal reservedReturnCreditAmount;
        private BigDecimal preparedLoyaltyAmount;
        private BigDecimal preparedReturnCreditAmount;
        private UUID prepareOperationId;
        private BigDecimal consumedLoyaltyAmount;
        private BigDecimal consumedReturnCreditAmount;
        private BigDecimal accountLoyaltyBalance;
        private BigDecimal accountReturnCreditBalance;
        private final List<DevReservedLot> reservedLots;
        private Instant heartbeatAt;
        private Instant leaseExpiresAt;

        private DevReservation(
                UUID id,
                ReserveRequest request,
                AvailableWallet wallet,
                Instant now) {
            this.id = id;
            companyId = request.companyId();
            storeId = request.storeId();
            memberId = request.memberId();
            terminalId = request.terminalId();
            saleId = request.saleId();
            status = "ACTIVE";
            reservedLoyaltyAmount = wallet.loyaltyBalance();
            reservedReturnCreditAmount = wallet.returnCreditBalance();
            preparedLoyaltyAmount = ZERO;
            preparedReturnCreditAmount = ZERO;
            consumedLoyaltyAmount = ZERO;
            consumedReturnCreditAmount = ZERO;
            accountLoyaltyBalance = wallet.loyaltyBalance();
            accountReturnCreditBalance = wallet.returnCreditBalance();
            reservedLots = wallet.reservedLots().stream()
                    .map(DevReservedLot::new)
                    .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
            heartbeatAt = now;
            leaseExpiresAt = now.plusSeconds(LEASE_SECONDS);
        }

        private DevReservation(
                LocalMemberBalanceReservation local,
                UUID companyId) {
            id = local.getCentralReservationId();
            this.companyId = companyId;
            storeId = local.getStoreId();
            memberId = local.getMemberId();
            terminalId = local.getTerminalId().toString();
            saleId = local.getSaleId();
            status = switch (local.getStatus()) {
                case ACTIVE, RELEASE_PENDING -> "ACTIVE";
                case PREPARED, TICKET_COMMITTED, FINALIZE_PENDING, ABORT_PENDING -> "PREPARED";
                case RELEASED -> "RELEASED";
                case EXPIRED -> "EXPIRED";
                case CONSUMED -> "CONSUMED";
            };
            reservedLoyaltyAmount = local.getReservedLoyaltyAmount();
            reservedReturnCreditAmount = local.getReservedReturnCreditAmount();
            preparedLoyaltyAmount = local.getPreparedLoyaltyAmount();
            preparedReturnCreditAmount = local.getPreparedReturnCreditAmount();
            prepareOperationId = local.getPrepareOperationId();
            consumedLoyaltyAmount = local.getConsumedLoyaltyAmount();
            consumedReturnCreditAmount = local.getConsumedReturnCreditAmount();
            accountLoyaltyBalance = local.getAccountLoyaltyBalance();
            accountReturnCreditBalance = local.getAccountReturnCreditBalance();
            reservedLots = new ArrayList<>();
            heartbeatAt = local.getHeartbeatAt();
            leaseExpiresAt = local.getLeaseExpiresAt();
        }

        private static DevReservation active(
                UUID id,
                ReserveRequest request,
                AvailableWallet wallet,
                Instant now) {
            return new DevReservation(id, request, wallet, now);
        }

        private static DevReservation restore(
                LocalMemberBalanceReservation local,
                UUID companyId) {
            return new DevReservation(local, companyId);
        }

        private boolean matches(ReserveRequest request) {
            return memberId.equals(request.memberId())
                    && matches(request.companyId(), request.storeId(),
                            request.terminalId(), request.saleId());
        }

        private boolean matches(
                UUID requestedCompanyId,
                UUID requestedStoreId,
                String requestedTerminalId,
                String requestedSaleId) {
            return companyId.equals(requestedCompanyId)
                    && storeId.equals(requestedStoreId)
                    && terminalId.equals(requestedTerminalId)
                    && saleId.equals(requestedSaleId);
        }

        private void expireIfNecessary(Instant now) {
            if ("ACTIVE".equals(status) && !leaseExpiresAt.isAfter(now)) {
                status = "EXPIRED";
            }
        }

        private void requirePrepared(UUID operationId) {
            if (!"PREPARED".equals(status)
                    || prepareOperationId == null
                    || !prepareOperationId.equals(operationId)) {
                throw new MemberBalanceCentralException(
                        MemberBalanceCentralException.Kind.REJECTED,
                        "La operacion no coincide con la reserva preparada");
            }
        }

        private void validatePreparedAmounts(
                BigDecimal loyaltyAmount,
                BigDecimal returnCreditAmount) {
            if (loyaltyAmount.signum() < 0 || returnCreditAmount.signum() < 0) {
                throw new MemberBalanceCentralException(
                        MemberBalanceCentralException.Kind.REJECTED,
                        "Los importes del monedero no pueden ser negativos");
            }
            if (loyaltyAmount.signum() == 0 && returnCreditAmount.signum() == 0) {
                throw new MemberBalanceCentralException(
                        MemberBalanceCentralException.Kind.REJECTED,
                        "Debe prepararse al menos un importe del monedero");
            }
            if (loyaltyAmount.compareTo(reservedLoyaltyAmount) > 0
                    || returnCreditAmount.compareTo(reservedReturnCreditAmount) > 0) {
                throw new MemberBalanceCentralException(
                        MemberBalanceCentralException.Kind.REJECTED,
                        "El importe supera el saldo reservado de su tipo");
            }
        }

        private void consumePreparedLots() {
            if (reservedLots.isEmpty()) {
                return;
            }
            consumeLots(MemberBalanceLotType.LOYALTY, preparedLoyaltyAmount);
            consumeLots(MemberBalanceLotType.RETURN_CREDIT, preparedReturnCreditAmount);
        }

        private void consumeLots(MemberBalanceLotType balanceType, BigDecimal requestedAmount) {
            BigDecimal remaining = requestedAmount;
            for (DevReservedLot lot : reservedLots) {
                if (lot.balanceType != balanceType || remaining.signum() == 0) {
                    continue;
                }
                BigDecimal consumed = lot.remainingAmount.min(remaining);
                lot.remainingAmount = lot.remainingAmount.subtract(consumed);
                remaining = remaining.subtract(consumed);
            }
            if (remaining.signum() != 0) {
                throw new MemberBalanceCentralException(
                        MemberBalanceCentralException.Kind.REJECTED,
                        "Los lotes reservados no cubren el importe preparado");
            }
        }

        private boolean closed() {
            return "RELEASED".equals(status)
                    || "EXPIRED".equals(status)
                    || "CONSUMED".equals(status);
        }

        private ReservationResponse response() {
            return new ReservationResponse(
                    id,
                    memberId,
                    status,
                    reservedLoyaltyAmount,
                    reservedReturnCreditAmount,
                    preparedLoyaltyAmount,
                    preparedReturnCreditAmount,
                    prepareOperationId,
                    consumedLoyaltyAmount,
                    consumedReturnCreditAmount,
                    accountLoyaltyBalance,
                    accountReturnCreditBalance,
                    reservedLots.stream().map(DevReservedLot::response).toList(),
                    heartbeatAt,
                    leaseExpiresAt,
                    HEARTBEAT_INTERVAL_SECONDS,
                    LEASE_SECONDS);
        }
    }

    private static final class DevReservedLot {
        private final MemberBalanceLotType balanceType;
        private final UUID lotId;
        private BigDecimal remainingAmount;
        private final Instant createdAt;
        private final Instant expiresAt;
        private final UUID sourceMovementId;
        private final UUID documentId;

        private DevReservedLot(ReservedLot lot) {
            balanceType = lot.balanceType();
            lotId = lot.lotId();
            remainingAmount = lot.remainingAmount();
            createdAt = lot.createdAt();
            expiresAt = lot.expiresAt();
            sourceMovementId = lot.sourceMovementId();
            documentId = lot.documentId();
        }

        private ReservedLot response() {
            return new ReservedLot(
                    balanceType,
                    lotId,
                    remainingAmount,
                    createdAt,
                    expiresAt,
                    sourceMovementId,
                    documentId);
        }
    }
}
