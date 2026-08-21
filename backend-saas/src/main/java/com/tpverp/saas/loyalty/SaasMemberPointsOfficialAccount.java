package com.tpverp.saas.loyalty;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name="saas_member_points_official_account")
public class SaasMemberPointsOfficialAccount {
    @Id private UUID id;
    @Column(name="bootstrap_id",nullable=false) private UUID bootstrapId;
    @Column(name="company_id",nullable=false) private UUID companyId;
    @Column(name="member_id",nullable=false) private UUID memberId;
    @Column(nullable=false,precision=19,scale=0) private BigDecimal points;
    @Column(name="points_debt",nullable=false,precision=19,scale=0) private BigDecimal pointsDebt;
    @Column(nullable=false) private long revision;
    @Column(name="central_watermark",nullable=false) private long centralWatermark;
    @Column(name="created_at",nullable=false) private Instant createdAt;
    protected SaasMemberPointsOfficialAccount() {}
    public SaasMemberPointsOfficialAccount(UUID id, UUID bootstrapId, UUID companyId, UUID memberId,
            BigDecimal points, BigDecimal pointsDebt, long revision, long watermark, Instant now) {
        this.id=id; this.bootstrapId=bootstrapId; this.companyId=companyId; this.memberId=memberId;
        this.points=points; this.pointsDebt=pointsDebt; this.revision=revision;
        this.centralWatermark=watermark; this.createdAt=now;
    }
    public UUID getMemberId(){return memberId;} public BigDecimal getPoints(){return points;}
    public BigDecimal getPointsDebt(){return pointsDebt;} public long getRevision(){return revision;}
    public long getCentralWatermark(){return centralWatermark;}
}
