package com.tpverp.saas.loyalty;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "saas_member_balance_retention_claim")
public class SaasMemberBalanceRetentionClaim {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reservation_id")
    private SaasMemberBalanceReservation reservation;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "receipt_id")
    private SaasMemberBalanceRetentionReceipt receipt;

    @Column(name = "lot_id", nullable = false)
    private UUID lotId;

    @Column(name = "source_movement_id", nullable = false)
    private UUID sourceMovementId;

    @Column(name = "source_document_id", nullable = false)
    private UUID sourceDocumentId;

    @Column(name = "amount_original", nullable = false, precision = 19, scale = 2)
    private BigDecimal amountOriginal;

    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "held_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal heldAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private SaasMemberBalanceRetentionClaimStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected SaasMemberBalanceRetentionClaim() {
    }

    public SaasMemberBalanceRetentionClaim(
            UUID id,
            SaasMemberBalanceReservation reservation,
            UUID lotId,
            UUID sourceMovementId,
            UUID sourceDocumentId,
            BigDecimal amountOriginal,
            BigDecimal amount,
            SaasMemberBalanceRetentionClaimStatus status,
            Instant now) {
        this.id = Objects.requireNonNull(id, "id");
        this.reservation = reservation;
        this.lotId = Objects.requireNonNull(lotId, "lotId");
        this.sourceMovementId = Objects.requireNonNull(sourceMovementId, "sourceMovementId");
        this.sourceDocumentId = Objects.requireNonNull(sourceDocumentId, "sourceDocumentId");
        this.amountOriginal = money(amountOriginal, "amountOriginal");
        this.amount = money(amount, "amount");
        if (this.amount.signum() <= 0 || this.amount.compareTo(this.amountOriginal) > 0) {
            throw new IllegalArgumentException("Importe de retencion invalido");
        }
        this.heldAmount = this.amount;
        this.status = Objects.requireNonNull(status, "status");
        this.createdAt = Objects.requireNonNull(now, "now");
        this.updatedAt = now;
    }

    public UUID getId() { return id; }
    public SaasMemberBalanceReservation getReservation() { return reservation; }
    public SaasMemberBalanceRetentionReceipt getReceipt() { return receipt; }
    public UUID getLotId() { return lotId; }
    public UUID getSourceMovementId() { return sourceMovementId; }
    public UUID getSourceDocumentId() { return sourceDocumentId; }
    public BigDecimal getAmountOriginal() { return amountOriginal; }
    public BigDecimal getAmount() { return amount; }
    public BigDecimal getHeldAmount() { return heldAmount; }

    public void setHeldAmount(BigDecimal heldAmount) {
        this.heldAmount = money(heldAmount, "heldAmount");
        if (this.heldAmount.signum() < 0 || this.heldAmount.compareTo(amount) > 0) {
            throw new IllegalArgumentException("heldAmount supera amount");
        }
    }

    public void replace(
            UUID sourceMovementId,
            UUID sourceDocumentId,
            BigDecimal amountOriginal,
            BigDecimal amount,
            BigDecimal heldAmount,
            SaasMemberBalanceRetentionClaimStatus status,
            Instant now) {
        this.sourceMovementId = Objects.requireNonNull(sourceMovementId, "sourceMovementId");
        this.sourceDocumentId = Objects.requireNonNull(sourceDocumentId, "sourceDocumentId");
        this.amountOriginal = money(amountOriginal, "amountOriginal");
        this.amount = money(amount, "amount");
        this.heldAmount = money(heldAmount, "heldAmount");
        if (this.amount.signum() <= 0 || this.amount.compareTo(this.amountOriginal) > 0
                || this.heldAmount.signum() < 0 || this.heldAmount.compareTo(this.amount) > 0) {
            throw new IllegalArgumentException("Importe de retencion invalido");
        }
        this.status = Objects.requireNonNull(status, "status");
        this.updatedAt = Objects.requireNonNull(now, "now");
    }

    public void attachReceipt(SaasMemberBalanceRetentionReceipt receipt) {
        this.receipt = Objects.requireNonNull(receipt, "receipt");
        // A claim has exactly one durable owner. Once committed, ownership
        // moves from the live reservation to the immutable operation receipt.
        this.reservation = null;
    }
    public SaasMemberBalanceRetentionClaimStatus getStatus() { return status; }

    public void markHeldKnown(Instant now) {
        if (status == SaasMemberBalanceRetentionClaimStatus.HELD_MISSING) {
            status = SaasMemberBalanceRetentionClaimStatus.HELD_KNOWN;
            updatedAt = Objects.requireNonNull(now, "now");
        }
    }

    public void commitPending(Instant now) {
        if (status == SaasMemberBalanceRetentionClaimStatus.HELD_KNOWN
                || status == SaasMemberBalanceRetentionClaimStatus.HELD_MISSING) {
            status = SaasMemberBalanceRetentionClaimStatus.COMMITTED_PENDING;
            updatedAt = Objects.requireNonNull(now, "now");
        }
    }

    public void apply(Instant now) {
        if (status == SaasMemberBalanceRetentionClaimStatus.HELD_KNOWN
                || status == SaasMemberBalanceRetentionClaimStatus.COMMITTED_PENDING) {
            status = SaasMemberBalanceRetentionClaimStatus.APPLIED;
            updatedAt = Objects.requireNonNull(now, "now");
        }
    }

    public void release(Instant now) {
        if (status == SaasMemberBalanceRetentionClaimStatus.HELD_KNOWN
                || status == SaasMemberBalanceRetentionClaimStatus.HELD_MISSING) {
            status = SaasMemberBalanceRetentionClaimStatus.CANCELLED;
            updatedAt = Objects.requireNonNull(now, "now");
        }
    }

    private static BigDecimal money(BigDecimal value, String field) {
        Objects.requireNonNull(value, field);
        try {
            return value.setScale(2, java.math.RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(field + " debe tener escala 2", exception);
        }
    }
}
