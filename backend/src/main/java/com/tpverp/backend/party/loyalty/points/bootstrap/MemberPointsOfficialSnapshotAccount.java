package com.tpverp.backend.party.loyalty.points.bootstrap;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "member_points_official_snapshot_account")
public class MemberPointsOfficialSnapshotAccount {
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
    @Column(name = "official_revision", nullable = false)
    private long officialRevision;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected MemberPointsOfficialSnapshotAccount() {
    }

    public MemberPointsOfficialSnapshotAccount(
            UUID snapshotId,
            UUID memberId,
            long points,
            long pointsDebt,
            long officialRevision,
            Instant createdAt) {
        this.id = UUID.nameUUIDFromBytes((snapshotId + "|O|" + memberId)
                .getBytes(StandardCharsets.UTF_8));
        this.snapshotId = snapshotId;
        this.memberId = memberId;
        this.points = points;
        this.pointsDebt = pointsDebt;
        this.officialRevision = officialRevision;
        this.createdAt = createdAt;
    }

    public static UUID deterministicId(UUID snapshotId, UUID memberId) {
        return UUID.nameUUIDFromBytes((snapshotId + "|O|" + memberId)
                .getBytes(StandardCharsets.UTF_8));
    }

    public UUID getSnapshotId() { return snapshotId; }
    public UUID getMemberId() { return memberId; }
    public long getPoints() { return points; }
    public long getPointsDebt() { return pointsDebt; }
    public long getOfficialRevision() { return officialRevision; }
}
