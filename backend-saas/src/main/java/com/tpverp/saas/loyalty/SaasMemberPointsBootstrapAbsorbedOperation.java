package com.tpverp.saas.loyalty;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "saas_member_points_bootstrap_absorbed_operation")
public class SaasMemberPointsBootstrapAbsorbedOperation {
    @Id private UUID id;
    @Column(name="bootstrap_id",nullable=false) private UUID bootstrapId;
    @Column(name="company_id",nullable=false) private UUID companyId;
    @Column(name="operation_id",nullable=false) private UUID operationId;
    @Column(name="contract_hash",nullable=false,length=64) private String contractHash;
    @Column(name="source_store_ids",nullable=false,columnDefinition="text") private String sourceStoreIds;
    @Column(name="absorbed_at",nullable=false) private Instant absorbedAt;
    protected SaasMemberPointsBootstrapAbsorbedOperation() {}
    public SaasMemberPointsBootstrapAbsorbedOperation(UUID id, UUID bootstrapId, UUID companyId,
            UUID operationId, String contractHash, String sourceStoreIds, Instant absorbedAt) {
        this.id=id; this.bootstrapId=bootstrapId; this.companyId=companyId; this.operationId=operationId;
        this.contractHash=contractHash; this.sourceStoreIds=sourceStoreIds; this.absorbedAt=absorbedAt;
    }
    public String getContractHash(){return contractHash;}
}
