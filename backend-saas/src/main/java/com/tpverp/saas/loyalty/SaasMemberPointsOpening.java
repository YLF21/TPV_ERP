package com.tpverp.saas.loyalty;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name="saas_member_points_opening")
public class SaasMemberPointsOpening {
    @Id private UUID id;
    @Column(name="bootstrap_id",nullable=false) private UUID bootstrapId;
    @Column(name="company_id",nullable=false) private UUID companyId;
    @Column(name="member_id",nullable=false) private UUID memberId;
    @Column(nullable=false,precision=19,scale=0) private BigDecimal points;
    @Column(name="points_debt",nullable=false,precision=19,scale=0) private BigDecimal pointsDebt;
    @Column(name="source_store_ids",nullable=false,columnDefinition="text") private String sourceStoreIds;
    @Column(name="source_checksum",nullable=false,length=64) private String sourceChecksum;
    @Column(name="applied_at",nullable=false) private Instant appliedAt;
    protected SaasMemberPointsOpening() {}
    public SaasMemberPointsOpening(UUID id, UUID bootstrapId, UUID companyId, UUID memberId,
            BigDecimal points, BigDecimal pointsDebt, String stores, String checksum, Instant now) {
        this.id=id; this.bootstrapId=bootstrapId; this.companyId=companyId; this.memberId=memberId;
        this.points=points; this.pointsDebt=pointsDebt; this.sourceStoreIds=stores;
        this.sourceChecksum=checksum; this.appliedAt=now;
    }
}
