package com.tpverp.backend.party.loyalty.points.bootstrap;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "member_points_bootstrap_snapshot")
public class MemberPointsBootstrapSnapshot {
    @Id
    @Column(name = "snapshot_id")
    private UUID snapshotId;
    @Column(name = "bootstrap_id", nullable = false)
    private UUID bootstrapId;
    @Column(name = "empresa_id", nullable = false)
    private UUID companyId;
    @Column(name = "tienda_id", nullable = false)
    private UUID storeId;
    @Column(name = "cutoff_at", nullable = false)
    private Instant cutoffAt;
    @Column(name = "cut_sequence", nullable = false)
    private long cutSequence;
    @Column(name = "account_chunk_count", nullable = false)
    private int accountChunkCount;
    @Column(name = "operation_chunk_count", nullable = false)
    private int operationChunkCount;
    @Column(name = "account_count", nullable = false)
    private long accountCount;
    @Column(name = "operation_count", nullable = false)
    private long operationCount;
    @Column(name = "snapshot_checksum", nullable = false, length = 64)
    private String snapshotChecksum;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected MemberPointsBootstrapSnapshot() {
    }

    public MemberPointsBootstrapSnapshot(
            UUID snapshotId, UUID bootstrapId, UUID companyId, UUID storeId,
            Instant cutoffAt, long cutSequence,
            int accountChunkCount, int operationChunkCount,
            long accountCount, long operationCount,
            String snapshotChecksum, Instant createdAt) {
        this.snapshotId = Objects.requireNonNull(snapshotId, "snapshotId");
        this.bootstrapId = Objects.requireNonNull(bootstrapId, "bootstrapId");
        this.companyId = Objects.requireNonNull(companyId, "companyId");
        this.storeId = Objects.requireNonNull(storeId, "storeId");
        this.cutoffAt = Objects.requireNonNull(cutoffAt, "cutoffAt");
        this.cutSequence = cutSequence;
        this.accountChunkCount = accountChunkCount;
        this.operationChunkCount = operationChunkCount;
        this.accountCount = accountCount;
        this.operationCount = operationCount;
        this.snapshotChecksum = Objects.requireNonNull(snapshotChecksum, "snapshotChecksum");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    public UUID getSnapshotId() { return snapshotId; }
    public UUID getBootstrapId() { return bootstrapId; }
    public UUID getCompanyId() { return companyId; }
    public UUID getStoreId() { return storeId; }
    public Instant getCutoffAt() { return cutoffAt; }
    public long getCutSequence() { return cutSequence; }
    public int getAccountChunkCount() { return accountChunkCount; }
    public int getOperationChunkCount() { return operationChunkCount; }
    public long getAccountCount() { return accountCount; }
    public long getOperationCount() { return operationCount; }
    public String getSnapshotChecksum() { return snapshotChecksum; }
    public Instant getCreatedAt() { return createdAt; }
}
