package com.tpverp.saas.loyalty;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "saas_member_points_bootstrap_staging_operation")
public class SaasMemberPointsBootstrapStagingOperation {
    @Id private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "snapshot_row_id", nullable = false) private SaasMemberPointsBootstrapSnapshot snapshot;
    @Column(nullable = false, length = 24) private String kind;
    @Column(name = "operation_id", nullable = false) private UUID operationId;
    @Column(name = "contract_hash", nullable = false, length = 64) private String contractHash;
    @Column(name = "source_sequence") private Long sourceSequence;
    protected SaasMemberPointsBootstrapStagingOperation() {}
    public SaasMemberPointsBootstrapStagingOperation(UUID id, SaasMemberPointsBootstrapSnapshot snapshot,
            String kind, UUID operationId, String contractHash, Long sourceSequence) {
        this.id=id; this.snapshot=snapshot; this.kind=kind; this.operationId=operationId;
        this.contractHash=contractHash; this.sourceSequence=sourceSequence;
    }
    public SaasMemberPointsBootstrapSnapshot getSnapshot(){return snapshot;} public String getKind(){return kind;}
    public UUID getOperationId(){return operationId;} public String getContractHash(){return contractHash;}
    public Long getSourceSequence(){return sourceSequence;}
}
