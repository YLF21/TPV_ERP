package com.tpverp.saas.loyalty;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "saas_member_points_bootstrap_staging_account")
public class SaasMemberPointsBootstrapStagingAccount {
    @Id private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "snapshot_row_id", nullable = false) private SaasMemberPointsBootstrapSnapshot snapshot;
    @Column(name = "member_id", nullable = false) private UUID memberId;
    @Column(nullable = false, precision = 19, scale = 0) private BigDecimal points;
    @Column(name = "points_debt", nullable = false, precision = 19, scale = 0) private BigDecimal pointsDebt;
    protected SaasMemberPointsBootstrapStagingAccount() {}
    public SaasMemberPointsBootstrapStagingAccount(UUID id, SaasMemberPointsBootstrapSnapshot snapshot,
            UUID memberId, BigDecimal points, BigDecimal pointsDebt) {
        this.id=id; this.snapshot=snapshot; this.memberId=memberId; this.points=points; this.pointsDebt=pointsDebt;
    }
    public SaasMemberPointsBootstrapSnapshot getSnapshot(){return snapshot;}
    public UUID getMemberId(){return memberId;} public BigDecimal getPoints(){return points;}
    public BigDecimal getPointsDebt(){return pointsDebt;}
}
