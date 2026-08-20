package com.tpverp.saas.loyalty;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "saas_member_points_bootstrap_snapshot")
public class SaasMemberPointsBootstrapSnapshot {
    public static final String COLLECTING = "COLLECTING";
    public static final String COMPLETED = "COMPLETED";
    public static final String CONFLICT = "CONFLICT";
    @Id private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "bootstrap_id", nullable = false) private SaasMemberPointsBootstrap bootstrap;
    @Column(name = "snapshot_id", nullable = false) private UUID snapshotId;
    @Column(name = "store_id", nullable = false) private UUID storeId;
    @Column(name = "cutoff_at", nullable = false) private Instant cutoffAt;
    @Column(name = "account_chunk_count", nullable = false) private int accountChunkCount;
    @Column(name = "absorbed_chunk_count", nullable = false) private int absorbedChunkCount;
    @Column(name = "replay_chunk_count", nullable = false) private int replayChunkCount;
    @Column(name = "account_count", nullable = false) private int accountCount;
    @Column(name = "absorbed_count", nullable = false) private int absorbedCount;
    @Column(name = "replay_count", nullable = false) private int replayCount;
    @Column(name = "snapshot_checksum", nullable = false, length = 64) private String snapshotChecksum;
    @Column(nullable = false, length = 20) private String status;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "completed_at") private Instant completedAt;
    @Column(name = "conflict_reason", columnDefinition = "text") private String conflictReason;
    @Version private long version;

    protected SaasMemberPointsBootstrapSnapshot() {}
    public SaasMemberPointsBootstrapSnapshot(UUID id, SaasMemberPointsBootstrap bootstrap,
            LoyaltyApiModels.PointsBootstrapBeginRequest request, String checksum, Instant now) {
        this.id=id; this.bootstrap=bootstrap; this.snapshotId=request.snapshotId(); this.storeId=request.storeId();
        this.cutoffAt=request.cutoffAt(); this.accountChunkCount=request.accountChunkCount();
        this.absorbedChunkCount=request.absorbedOperationChunkCount();
        this.replayChunkCount=request.replayOperationChunkCount(); this.accountCount=request.accountCount();
        this.absorbedCount=request.absorbedOperationCount(); this.replayCount=request.replayOperationCount();
        this.snapshotChecksum=checksum; this.status=COLLECTING; this.createdAt=now;
    }
    public UUID getId(){return id;} public UUID getSnapshotId(){return snapshotId;}
    public UUID getStoreId(){return storeId;} public Instant getCutoffAt(){return cutoffAt;}
    public int getAccountChunkCount(){return accountChunkCount;} public int getAbsorbedChunkCount(){return absorbedChunkCount;}
    public int getReplayChunkCount(){return replayChunkCount;} public int getAccountCount(){return accountCount;}
    public int getAbsorbedCount(){return absorbedCount;} public int getReplayCount(){return replayCount;}
    public String getSnapshotChecksum(){return snapshotChecksum;} public boolean isCompleted(){return COMPLETED.equals(status);}
    public boolean matches(LoyaltyApiModels.PointsBootstrapBeginRequest r, String checksum) {
        return snapshotId.equals(r.snapshotId()) && storeId.equals(r.storeId()) && cutoffAt.equals(r.cutoffAt())
            && accountChunkCount==r.accountChunkCount() && absorbedChunkCount==r.absorbedOperationChunkCount()
            && replayChunkCount==r.replayOperationChunkCount() && accountCount==r.accountCount()
            && absorbedCount==r.absorbedOperationCount() && replayCount==r.replayOperationCount()
            && snapshotChecksum.equals(checksum);
    }
    public void complete(Instant now){status=COMPLETED; completedAt=now;}
    public void markConflict(String reason){status=CONFLICT; conflictReason=reason;}
}
