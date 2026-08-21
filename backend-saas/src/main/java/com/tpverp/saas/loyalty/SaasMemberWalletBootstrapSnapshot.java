package com.tpverp.saas.loyalty;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "saas_member_wallet_bootstrap_snapshot")
public class SaasMemberWalletBootstrapSnapshot {

    public static final String COLLECTING = "COLLECTING";
    public static final String COMPLETED = "COMPLETED";
    public static final String CONFLICT = "CONFLICT";

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "bootstrap_id", nullable = false)
    private SaasMemberWalletBootstrap bootstrap;

    @Column(name = "snapshot_id", nullable = false)
    private UUID snapshotId;

    @Column(name = "store_id", nullable = false)
    private UUID storeId;

    @Column(name = "cutoff_at", nullable = false)
    private Instant cutoffAt;

    @Column(name = "account_chunk_count", nullable = false)
    private int accountChunkCount;

    @Column(name = "lot_chunk_count", nullable = false)
    private int lotChunkCount;

    @Column(name = "account_count", nullable = false)
    private int accountCount;

    @Column(name = "lot_count", nullable = false)
    private int lotCount;

    @Column(name = "snapshot_checksum", nullable = false, length = 64)
    private String snapshotChecksum;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "conflict_reason", columnDefinition = "text")
    private String conflictReason;

    @Version
    private long version;

    protected SaasMemberWalletBootstrapSnapshot() {
    }

    public SaasMemberWalletBootstrapSnapshot(
            UUID id,
            SaasMemberWalletBootstrap bootstrap,
            LoyaltyApiModels.WalletBootstrapBeginRequest request,
            String snapshotChecksum,
            Instant createdAt) {
        this.id = id;
        this.bootstrap = bootstrap;
        this.snapshotId = request.snapshotId();
        this.storeId = request.storeId();
        this.cutoffAt = request.cutoffAt();
        this.accountChunkCount = request.accountChunkCount();
        this.lotChunkCount = request.lotChunkCount();
        this.accountCount = request.accountCount();
        this.lotCount = request.lotCount();
        this.snapshotChecksum = snapshotChecksum;
        this.status = COLLECTING;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getSnapshotId() {
        return snapshotId;
    }

    public UUID getStoreId() {
        return storeId;
    }

    public Instant getCutoffAt() {
        return cutoffAt;
    }

    public int getAccountChunkCount() {
        return accountChunkCount;
    }

    public int getLotChunkCount() {
        return lotChunkCount;
    }

    public int getAccountCount() {
        return accountCount;
    }

    public int getLotCount() {
        return lotCount;
    }

    public String getSnapshotChecksum() {
        return snapshotChecksum;
    }

    public String getStatus() {
        return status;
    }

    public boolean isCompleted() {
        return COMPLETED.equals(status);
    }

    public boolean matches(
            LoyaltyApiModels.WalletBootstrapBeginRequest request,
            String checksum) {
        return storeId.equals(request.storeId())
                && snapshotId.equals(request.snapshotId())
                && cutoffAt.equals(request.cutoffAt())
                && accountChunkCount == request.accountChunkCount()
                && lotChunkCount == request.lotChunkCount()
                && accountCount == request.accountCount()
                && lotCount == request.lotCount()
                && snapshotChecksum.equals(checksum);
    }

    public void complete(Instant now) {
        status = COMPLETED;
        completedAt = now;
    }

    public void markConflict(String reason) {
        status = CONFLICT;
        conflictReason = reason;
    }
}
