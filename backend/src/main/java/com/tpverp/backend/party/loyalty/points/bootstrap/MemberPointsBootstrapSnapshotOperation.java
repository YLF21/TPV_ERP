package com.tpverp.backend.party.loyalty.points.bootstrap;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

@Entity
@Table(name = "member_points_bootstrap_operation")
public class MemberPointsBootstrapSnapshotOperation {
    @Id
    private UUID id;
    @Column(name = "snapshot_id", nullable = false)
    private UUID snapshotId;
    @Column(name = "operation_id", nullable = false)
    private UUID operationId;
    @Column(name = "contract_hash", nullable = false, length = 64)
    private String contractHash;
    @Column(name = "source_sequence", nullable = false)
    private long sourceSequence;

    protected MemberPointsBootstrapSnapshotOperation() {
    }

    public MemberPointsBootstrapSnapshotOperation(
            UUID snapshotId, UUID operationId, String contractHash, long sourceSequence) {
        this.id = UUID.nameUUIDFromBytes((snapshotId + "|I|" + operationId)
                .getBytes(StandardCharsets.UTF_8));
        this.snapshotId = snapshotId;
        this.operationId = operationId;
        this.contractHash = contractHash;
        this.sourceSequence = sourceSequence;
    }

    public UUID getSnapshotId() { return snapshotId; }
    public UUID getOperationId() { return operationId; }
    public String getContractHash() { return contractHash; }
    public long getSourceSequence() { return sourceSequence; }
}
