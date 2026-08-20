package com.tpverp.backend.party.loyalty.points.bootstrap;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

@Entity
@Table(name = "member_points_bootstrap_account")
public class MemberPointsBootstrapSnapshotAccount {
    @Id
    private UUID id;
    @Column(name = "snapshot_id", nullable = false)
    private UUID snapshotId;
    @Column(name = "member_id", nullable = false)
    private UUID memberId;
    @Column(nullable = false)
    private long points;
    @Column(name = "points_debt", nullable = false)
    private long pointsDebt;

    protected MemberPointsBootstrapSnapshotAccount() {
    }

    public MemberPointsBootstrapSnapshotAccount(
            UUID snapshotId, UUID memberId, long points, long pointsDebt) {
        this.id = UUID.nameUUIDFromBytes((snapshotId + "|A|" + memberId)
                .getBytes(StandardCharsets.UTF_8));
        this.snapshotId = snapshotId;
        this.memberId = memberId;
        this.points = points;
        this.pointsDebt = pointsDebt;
    }

    public UUID getSnapshotId() { return snapshotId; }
    public UUID getMemberId() { return memberId; }
    public long getPoints() { return points; }
    public long getPointsDebt() { return pointsDebt; }
}
