package com.tpverp.backend.party.loyalty.central;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "member_balance_reservation_local")
public class LocalMemberBalanceReservation {

    @Id
    private UUID id;

    @Column(name = "central_reservation_id", nullable = false, unique = true)
    private UUID centralReservationId;

    @Column(name = "store_id", nullable = false)
    private UUID storeId;

    @Column(name = "terminal_id", nullable = false)
    private UUID terminalId;

    @Column(name = "member_id", nullable = false)
    private UUID memberId;

    @Column(name = "sale_id", nullable = false, length = 120)
    private String saleId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private LocalMemberBalanceReservationStatus status;

    @Column(name = "reserved_total", nullable = false, precision = 19, scale = 2)
    private BigDecimal reservedTotal;

    @Column(name = "reserved_loyalty_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal reservedLoyaltyAmount;

    @Column(name = "reserved_return_credit_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal reservedReturnCreditAmount;

    @Column(name = "prepared_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal preparedAmount;

    @Column(name = "prepared_loyalty_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal preparedLoyaltyAmount;

    @Column(name = "prepared_return_credit_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal preparedReturnCreditAmount;

    @Column(name = "prepare_operation_id")
    private UUID prepareOperationId;

    @Column(name = "retention_revision", nullable = false)
    private long retentionRevision;

    @Column(name = "retention_fingerprint", nullable = false, length = 64)
    private String retentionFingerprint = "";

    @Column(name = "retention_attributed_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal retentionAttributedAmount = BigDecimal.ZERO.setScale(2);

    @Column(name = "retention_held_known", nullable = false, precision = 19, scale = 2)
    private BigDecimal retentionHeldKnown = BigDecimal.ZERO.setScale(2);

    @Column(name = "retention_pending_missing", nullable = false, precision = 19, scale = 2)
    private BigDecimal retentionPendingMissing = BigDecimal.ZERO.setScale(2);

    @Column(name = "retention_spent_shortfall", nullable = false, precision = 19, scale = 2)
    private BigDecimal retentionSpentShortfall = BigDecimal.ZERO.setScale(2);

    @Column(name = "retention_spendable", nullable = false, precision = 19, scale = 2)
    private BigDecimal retentionSpendable = BigDecimal.ZERO.setScale(2);

    @Column(name = "retention_recovered_known", nullable = false, precision = 19, scale = 2)
    private BigDecimal retentionRecoveredKnown = BigDecimal.ZERO.setScale(2);

    /** Immutable final-reservation lot photo used by offline capacity checks. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "retention_reserved_lots", nullable = false, columnDefinition = "jsonb")
    private List<RetentionReservedLotSnapshot> retentionReservedLots = List.of();

    @Column(name = "ticket_id")
    private UUID ticketId;

    @Column(name = "consumed_total", nullable = false, precision = 19, scale = 2)
    private BigDecimal consumedTotal;

    @Column(name = "consumed_loyalty_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal consumedLoyaltyAmount;

    @Column(name = "consumed_return_credit_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal consumedReturnCreditAmount;

    @Column(name = "account_balance", nullable = false, precision = 19, scale = 2)
    private BigDecimal accountBalance;

    @Column(name = "account_loyalty_balance", nullable = false, precision = 19, scale = 2)
    private BigDecimal accountLoyaltyBalance;

    @Column(name = "account_return_credit_balance", nullable = false, precision = 19, scale = 2)
    private BigDecimal accountReturnCreditBalance;

    @Column(name = "heartbeat_at", nullable = false)
    private Instant heartbeatAt;

    @Column(name = "lease_expires_at", nullable = false)
    private Instant leaseExpiresAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Version
    private long version;

    protected LocalMemberBalanceReservation() {
    }

    public static LocalMemberBalanceReservation create(
            UUID storeId,
            UUID terminalId,
            UUID memberId,
            String saleId,
            MemberBalanceCentralGateway.ReservationResponse central,
            Instant now) {
        LocalMemberBalanceReservation reservation = new LocalMemberBalanceReservation();
        reservation.id = UUID.randomUUID();
        reservation.storeId = Objects.requireNonNull(storeId, "storeId");
        reservation.terminalId = Objects.requireNonNull(terminalId, "terminalId");
        reservation.memberId = Objects.requireNonNull(memberId, "memberId");
        reservation.saleId = requireSaleId(saleId);
        reservation.createdAt = Objects.requireNonNull(now, "now");
        reservation.apply(central, now);
        return reservation;
    }

    public void apply(MemberBalanceCentralGateway.ReservationResponse central, Instant now) {
        Objects.requireNonNull(central, "central");
        if (!memberId.equals(central.memberId())) {
            throw new MemberBalanceCentralException(
                    MemberBalanceCentralException.Kind.CONFLICT,
                    "member_balance_reservation_member_mismatch");
        }
        if (centralReservationId != null && !centralReservationId.equals(central.reservationId())) {
            throw new IllegalStateException("No se puede sustituir el identificador de una reserva central");
        }
        centralReservationId = central.reservationId();
        status = switch (central.status()) {
            case "ACTIVE" -> LocalMemberBalanceReservationStatus.ACTIVE;
            case "PREPARED" -> LocalMemberBalanceReservationStatus.PREPARED;
            case "RELEASED" -> LocalMemberBalanceReservationStatus.RELEASED;
            case "EXPIRED" -> LocalMemberBalanceReservationStatus.EXPIRED;
            case "CONSUMED" -> LocalMemberBalanceReservationStatus.CONSUMED;
            default -> throw new IllegalStateException("Estado central de reserva desconocido: " + central.status());
        };
        reservedLoyaltyAmount = central.reservedLoyaltyAmount();
        reservedReturnCreditAmount = central.reservedReturnCreditAmount();
        preparedLoyaltyAmount = central.preparedLoyaltyAmount();
        preparedReturnCreditAmount = central.preparedReturnCreditAmount();
        prepareOperationId = central.prepareOperationId();
        retentionRevision = central.retentionRevision();
        retentionFingerprint = central.retentionFingerprint();
        retentionHeldKnown = central.heldKnown();
        retentionPendingMissing = central.pendingMissing();
        retentionSpentShortfall = central.spentShortfall();
        retentionSpendable = central.spendable();
        retentionRecoveredKnown = central.recoveredKnown();
        var retentionClaims = central.retentionClaims() == null
                ? List.<MemberBalanceCentralGateway.RetentionClaim>of()
                : central.retentionClaims();
        // The aggregate retention metrics are not sufficient to authorize a
        // checkout: F10 must know exactly which source lots are held. A
        // central response that reports a hold without serialized claims is
        // an incompatible contract and must remain retryable.
        if ((retentionAttributedAmount(central).signum() > 0
                || central.heldKnown().signum() > 0
                || central.pendingMissing().signum() > 0)
                && retentionClaims.isEmpty()) {
            throw new MemberBalanceCentralException(
                    MemberBalanceCentralException.Kind.INVALID_RESPONSE,
                    "member_balance_retention_contract_incompatible");
        }
        var serializedHeld = retentionClaims.stream()
                .map(value -> value.heldAmount() == null
                        ? BigDecimal.ZERO.setScale(2) : value.heldAmount())
                .reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
        if (serializedHeld.compareTo(central.heldKnown()) != 0) {
            throw new MemberBalanceCentralException(
                    MemberBalanceCentralException.Kind.INVALID_RESPONSE,
                    "member_balance_retention_contract_incompatible");
        }
        retentionReservedLots = central.reservedLots().stream()
                .map(value -> new RetentionReservedLotSnapshot(
                        value.lotId(), value.balanceType().name(), value.remainingAmount(),
                        retentionClaims.stream()
                                .filter(claim -> claim.lotId().equals(value.lotId()))
                                .map(claim -> claim.heldAmount() == null
                                        ? BigDecimal.ZERO.setScale(2) : claim.heldAmount())
                                .reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add),
                        value.sourceMovementId(), value.documentId()))
                .toList();
        retentionAttributedAmount = retentionAttributedAmount(central);
        consumedLoyaltyAmount = central.consumedLoyaltyAmount();
        consumedReturnCreditAmount = central.consumedReturnCreditAmount();
        accountLoyaltyBalance = central.accountLoyaltyBalance();
        accountReturnCreditBalance = central.accountReturnCreditBalance();
        reservedTotal = central.reservedTotal();
        preparedAmount = central.preparedAmount();
        consumedTotal = central.consumedTotal();
        // V164 keeps the legacy column as the loyalty bucket. The public
        // getter below exposes the typed total without violating that check.
        accountBalance = central.accountLoyaltyBalance();
        heartbeatAt = central.heartbeatAt();
        leaseExpiresAt = central.leaseExpiresAt();
        updatedAt = Objects.requireNonNull(now, "now");
        closedAt = isClosed() ? now : null;
    }

    private static BigDecimal retentionAttributedAmount(
            MemberBalanceCentralGateway.ReservationResponse central) {
        return central.retentionAttributedAmount() == null
                ? central.heldKnown().add(central.pendingMissing())
                        .add(central.spentShortfall()).add(central.recoveredKnown())
                : central.retentionAttributedAmount();
    }

    public void markReleasePending(Instant now) {
        if (status == LocalMemberBalanceReservationStatus.ACTIVE
                || status == LocalMemberBalanceReservationStatus.RELEASE_PENDING) {
            status = LocalMemberBalanceReservationStatus.RELEASE_PENDING;
            updatedAt = Objects.requireNonNull(now, "now");
        }
    }

    /**
     * Closes the local ownership when the central release/abort is already
     * gone. A central 404 is only idempotent for these release operations;
     * prepare/finalize callers must never use this shortcut.
     */
    public void markReleaseConfirmed(Instant now) {
        if (isClosed()) {
            return;
        }
        if (status != LocalMemberBalanceReservationStatus.ACTIVE
                && status != LocalMemberBalanceReservationStatus.RELEASE_PENDING) {
            throw new IllegalStateException("La reserva no admite cierre idempotente de liberacion");
        }
        status = LocalMemberBalanceReservationStatus.RELEASED;
        updatedAt = Objects.requireNonNull(now, "now");
        closedAt = now;
    }

    public void markTicketCommitted(UUID ticketId, Instant now) {
        UUID value = Objects.requireNonNull(ticketId, "ticketId");
        if (this.ticketId != null) {
            if (!this.ticketId.equals(value)) {
                throw new IllegalStateException("La reserva ya esta vinculada a otro ticket");
            }
            if (status == LocalMemberBalanceReservationStatus.TICKET_COMMITTED
                    || status == LocalMemberBalanceReservationStatus.FINALIZE_PENDING
                    || status == LocalMemberBalanceReservationStatus.CONSUMED) {
                return;
            }
        }
        if (status != LocalMemberBalanceReservationStatus.PREPARED) {
            throw new IllegalStateException("La reserva no esta preparada para vincular un ticket");
        }
        this.ticketId = value;
        status = LocalMemberBalanceReservationStatus.TICKET_COMMITTED;
        updatedAt = Objects.requireNonNull(now, "now");
    }

    public void markFinalizePending(Instant now) {
        if (status != LocalMemberBalanceReservationStatus.TICKET_COMMITTED
                && status != LocalMemberBalanceReservationStatus.FINALIZE_PENDING) {
            throw new IllegalStateException("La reserva no admite finalizacion pendiente");
        }
        status = LocalMemberBalanceReservationStatus.FINALIZE_PENDING;
        updatedAt = Objects.requireNonNull(now, "now");
    }

    public void markAbortPending(Instant now) {
        if (status != LocalMemberBalanceReservationStatus.PREPARED
                && status != LocalMemberBalanceReservationStatus.ABORT_PENDING) {
            throw new IllegalStateException("La reserva no admite aborto pendiente");
        }
        status = LocalMemberBalanceReservationStatus.ABORT_PENDING;
        updatedAt = Objects.requireNonNull(now, "now");
    }

    public void markExpired(Instant now) {
        if (!isClosed()) {
            status = LocalMemberBalanceReservationStatus.EXPIRED;
            updatedAt = Objects.requireNonNull(now, "now");
            closedAt = now;
        }
    }

    public boolean leaseExpiredAt(Instant now) {
        return (status == LocalMemberBalanceReservationStatus.ACTIVE
                || status == LocalMemberBalanceReservationStatus.RELEASE_PENDING)
                && !leaseExpiresAt.isAfter(now);
    }

    public boolean isActive() {
        return status == LocalMemberBalanceReservationStatus.ACTIVE;
    }

    public boolean isClosed() {
        return status == LocalMemberBalanceReservationStatus.RELEASED
                || status == LocalMemberBalanceReservationStatus.EXPIRED
                || status == LocalMemberBalanceReservationStatus.CONSUMED;
    }

    public boolean matches(UUID storeId, UUID terminalId, String saleId) {
        return this.storeId.equals(storeId)
                && this.terminalId.equals(terminalId)
                && this.saleId.equals(requireSaleId(saleId));
    }

    public UUID getId() {
        return id;
    }

    public UUID getCentralReservationId() {
        return centralReservationId;
    }

    public UUID getStoreId() {
        return storeId;
    }

    public UUID getTerminalId() {
        return terminalId;
    }

    public UUID getMemberId() {
        return memberId;
    }

    public String getSaleId() {
        return saleId;
    }

    public LocalMemberBalanceReservationStatus getStatus() {
        return status;
    }

    public BigDecimal getReservedTotal() {
        return reservedTotal;
    }

    public BigDecimal getReservedLoyaltyAmount() {
        return reservedLoyaltyAmount;
    }

    public BigDecimal getReservedReturnCreditAmount() {
        return reservedReturnCreditAmount;
    }

    public BigDecimal getConsumedTotal() {
        return consumedTotal;
    }

    public BigDecimal getConsumedLoyaltyAmount() {
        return consumedLoyaltyAmount;
    }

    public BigDecimal getConsumedReturnCreditAmount() {
        return consumedReturnCreditAmount;
    }

    public BigDecimal getPreparedAmount() {
        return preparedAmount;
    }

    public BigDecimal getPreparedLoyaltyAmount() {
        return preparedLoyaltyAmount;
    }

    public BigDecimal getPreparedReturnCreditAmount() {
        return preparedReturnCreditAmount;
    }

    public UUID getPrepareOperationId() {
        return prepareOperationId;
    }

    public long getRetentionRevision() { return retentionRevision; }
    public String getRetentionFingerprint() { return retentionFingerprint; }
    public BigDecimal getRetentionAttributedAmount() { return retentionAttributedAmount; }
    public BigDecimal getRetentionHeldKnown() { return retentionHeldKnown; }
    public BigDecimal getRetentionPendingMissing() { return retentionPendingMissing; }
    public BigDecimal getRetentionSpentShortfall() { return retentionSpentShortfall; }
    public BigDecimal getRetentionSpendable() { return retentionSpendable; }
    public BigDecimal getRetentionRecoveredKnown() { return retentionRecoveredKnown; }
    public List<RetentionReservedLotSnapshot> getRetentionReservedLots() {
        return List.copyOf(retentionReservedLots);
    }

    public BigDecimal retentionSnapshotRemaining(UUID lotId) {
        return retentionReservedLots.stream()
                .filter(value -> value.lotId().equals(lotId))
                .map(RetentionReservedLotSnapshot::remainingAmount)
                .findFirst()
                .orElse(null);
    }

    public BigDecimal retentionSnapshotKnownAmount(UUID lotId, BigDecimal requested) {
        return retentionReservedLots.stream()
                .filter(value -> value.lotId().equals(lotId)
                        && "LOYALTY".equals(value.balanceType()))
                .map(value -> value.heldAmount().min(requested))
                .findFirst()
                .orElse(BigDecimal.ZERO.setScale(2));
    }

    public UUID getTicketId() {
        return ticketId;
    }

    public BigDecimal getAccountBalance() {
        return accountLoyaltyBalance.add(accountReturnCreditBalance);
    }

    public BigDecimal getAccountLoyaltyBalance() {
        return accountLoyaltyBalance;
    }

    public BigDecimal getAccountReturnCreditBalance() {
        return accountReturnCreditBalance;
    }

    public Instant getHeartbeatAt() {
        return heartbeatAt;
    }

    public Instant getLeaseExpiresAt() {
        return leaseExpiresAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getClosedAt() {
        return closedAt;
    }

    private static String requireSaleId(String value) {
        if (value == null || value.isBlank() || value.length() > 120) {
            throw new IllegalArgumentException("saleId es obligatorio y admite hasta 120 caracteres");
        }
        return value.trim();
    }

    public record RetentionReservedLotSnapshot(
            UUID lotId,
            String balanceType,
            BigDecimal remainingAmount,
            BigDecimal heldAmount,
            UUID sourceMovementId,
            UUID documentId) {
        public RetentionReservedLotSnapshot(
                UUID lotId,
                String balanceType,
                BigDecimal remainingAmount,
                UUID sourceMovementId,
                UUID documentId) {
            this(lotId, balanceType, remainingAmount, BigDecimal.ZERO.setScale(2),
                    sourceMovementId, documentId);
        }

        public RetentionReservedLotSnapshot {
            Objects.requireNonNull(lotId, "lotId");
            if (!"LOYALTY".equals(balanceType) && !"RETURN_CREDIT".equals(balanceType)) {
                throw new IllegalArgumentException("balanceType de snapshot desconocido");
            }
            try {
                remainingAmount = Objects.requireNonNull(remainingAmount, "remainingAmount")
                        .setScale(2, java.math.RoundingMode.UNNECESSARY);
            } catch (ArithmeticException exception) {
                throw new IllegalArgumentException("remainingAmount debe tener escala 2", exception);
            }
            if (remainingAmount.signum() < 0) {
                throw new IllegalArgumentException("remainingAmount no puede ser negativo");
            }
            try {
                heldAmount = (heldAmount == null ? BigDecimal.ZERO : heldAmount)
                        .setScale(2, java.math.RoundingMode.UNNECESSARY);
            } catch (ArithmeticException exception) {
                throw new IllegalArgumentException("heldAmount debe tener escala 2", exception);
            }
            if (heldAmount.signum() < 0 || heldAmount.compareTo(remainingAmount) > 0) {
                throw new IllegalArgumentException("heldAmount no puede superar remainingAmount");
            }
        }
    }
}
