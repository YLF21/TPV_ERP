package com.tpverp.backend.party.loyalty.bootstrap;

import com.tpverp.backend.party.MemberBalanceLotType;
import com.tpverp.backend.party.loyalty.central.MemberBalanceCentralGateway.SnapshotLot;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(
        name = "member_wallet_bootstrap_snapshot_lot",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_member_wallet_bootstrap_lot",
                columnNames = {"snapshot_id", "lot_id"}))
public class MemberWalletBootstrapSnapshotLot {

    @Id
    private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "snapshot_id", nullable = false)
    private MemberWalletBootstrapSnapshot snapshot;
    @Column(name = "lot_id", nullable = false)
    private UUID lotId;
    @Column(name = "member_id", nullable = false)
    private UUID memberId;
    @Enumerated(EnumType.STRING)
    @Column(name = "balance_type", nullable = false, length = 24)
    private MemberBalanceLotType balanceType;
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

    protected MemberWalletBootstrapSnapshotLot() {
    }

    public MemberWalletBootstrapSnapshotLot(
            MemberWalletBootstrapSnapshot snapshot,
            SnapshotLot lot) {
        this.id = UUID.randomUUID();
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
        this.lotId = lot.lotId();
        this.memberId = lot.memberId();
        this.balanceType = lot.balanceType();
        this.originalAmount = lot.originalAmount();
        this.remainingAmount = lot.remainingAmount();
        this.createdAt = lot.createdAt();
        this.expiresAt = lot.expiresAt();
        this.sourceMovementId = lot.sourceMovementId();
        this.documentId = lot.documentId();
    }

    public SnapshotLot toContract() {
        return new SnapshotLot(
                lotId,
                memberId,
                balanceType,
                originalAmount,
                remainingAmount,
                createdAt,
                expiresAt,
                sourceMovementId,
                documentId);
    }
}
