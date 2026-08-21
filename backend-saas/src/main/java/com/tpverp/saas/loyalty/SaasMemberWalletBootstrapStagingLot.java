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
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "saas_member_wallet_bootstrap_staging_lot")
public class SaasMemberWalletBootstrapStagingLot {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "snapshot_row_id", nullable = false)
    private SaasMemberWalletBootstrapSnapshot snapshot;

    @Column(name = "lot_id", nullable = false)
    private UUID lotId;

    @Column(name = "member_id", nullable = false)
    private UUID memberId;

    @Enumerated(EnumType.STRING)
    @Column(name = "balance_type", nullable = false, length = 20)
    private MemberBalanceType balanceType;

    @Column(name = "original_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal originalAmount;

    @Column(name = "remaining_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal remainingAmount;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "source_movement_id")
    private UUID sourceMovementId;

    @Column(name = "document_id")
    private UUID documentId;

    protected SaasMemberWalletBootstrapStagingLot() {
    }

    public SaasMemberWalletBootstrapStagingLot(
            UUID id,
            SaasMemberWalletBootstrapSnapshot snapshot,
            LoyaltyApiModels.SnapshotLot lot,
            BigDecimal originalAmount,
            BigDecimal remainingAmount) {
        this.id = id;
        this.snapshot = snapshot;
        this.lotId = lot.lotId();
        this.memberId = lot.memberId();
        this.balanceType = lot.balanceType();
        this.originalAmount = originalAmount;
        this.remainingAmount = remainingAmount;
        this.createdAt = lot.createdAt();
        this.expiresAt = lot.expiresAt();
        this.sourceMovementId = lot.sourceMovementId();
        this.documentId = lot.documentId();
    }

    public SaasMemberWalletBootstrapSnapshot getSnapshot() {
        return snapshot;
    }

    public UUID getLotId() {
        return lotId;
    }

    public UUID getMemberId() {
        return memberId;
    }

    public MemberBalanceType getBalanceType() {
        return balanceType;
    }

    public BigDecimal getOriginalAmount() {
        return originalAmount;
    }

    public BigDecimal getRemainingAmount() {
        return remainingAmount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public UUID getSourceMovementId() {
        return sourceMovementId;
    }

    public UUID getDocumentId() {
        return documentId;
    }
}
