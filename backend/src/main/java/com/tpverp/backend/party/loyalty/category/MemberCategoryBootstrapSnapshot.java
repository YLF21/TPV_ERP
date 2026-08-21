package com.tpverp.backend.party.loyalty.category;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "member_category_bootstrap_snapshot")
public class MemberCategoryBootstrapSnapshot {
    @Id
    @Column(name = "snapshot_id")
    private UUID snapshotId;
    @Column(name = "bootstrap_id", nullable = false)
    private UUID bootstrapId;
    @Column(name = "empresa_id", nullable = false)
    private UUID companyId;
    @Column(name = "tienda_id", nullable = false)
    private UUID storeId;
    @Column(name = "category_count", nullable = false)
    private int categoryCount;
    @Column(name = "assignment_count", nullable = false)
    private int assignmentCount;
    @Column(name = "category_hash", nullable = false, length = 64)
    private String categoryHash;
    @Column(name = "assignment_hash", nullable = false, length = 64)
    private String assignmentHash;
    @Column(name = "snapshot_checksum", nullable = false, length = 64)
    private String snapshotChecksum;
    @Column(name = "captured_at", nullable = false)
    private Instant capturedAt;

    protected MemberCategoryBootstrapSnapshot() {
    }

    public MemberCategoryBootstrapSnapshot(
            UUID snapshotId,
            UUID bootstrapId,
            UUID companyId,
            UUID storeId,
            int categoryCount,
            int assignmentCount,
            String categoryHash,
            String assignmentHash,
            String snapshotChecksum,
            Instant capturedAt) {
        this.snapshotId = Objects.requireNonNull(snapshotId, "snapshotId");
        this.bootstrapId = Objects.requireNonNull(bootstrapId, "bootstrapId");
        this.companyId = Objects.requireNonNull(companyId, "companyId");
        this.storeId = Objects.requireNonNull(storeId, "storeId");
        this.categoryCount = categoryCount;
        this.assignmentCount = assignmentCount;
        this.categoryHash = Objects.requireNonNull(categoryHash, "categoryHash");
        this.assignmentHash = Objects.requireNonNull(assignmentHash, "assignmentHash");
        this.snapshotChecksum = Objects.requireNonNull(snapshotChecksum, "snapshotChecksum");
        this.capturedAt = Objects.requireNonNull(capturedAt, "capturedAt");
    }

    public UUID getSnapshotId() { return snapshotId; }
    public UUID getBootstrapId() { return bootstrapId; }
    public UUID getCompanyId() { return companyId; }
    public UUID getStoreId() { return storeId; }
    public int getCategoryCount() { return categoryCount; }
    public int getAssignmentCount() { return assignmentCount; }
    public String getCategoryHash() { return categoryHash; }
    public String getAssignmentHash() { return assignmentHash; }
    public String getSnapshotChecksum() { return snapshotChecksum; }
    public Instant getCapturedAt() { return capturedAt; }
}
