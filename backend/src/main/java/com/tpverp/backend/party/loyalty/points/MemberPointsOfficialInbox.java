package com.tpverp.backend.party.loyalty.points;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "member_points_official_inbox")
public class MemberPointsOfficialInbox {
    @Id
    private UUID id;
    @Column(name = "tienda_id", nullable = false)
    private UUID storeId;
    @Column(name = "empresa_id", nullable = false)
    private UUID companyId;
    @Column(name = "miembro_id", nullable = false)
    private UUID memberId;
    @Column(nullable = false)
    private long points;
    @Column(name = "points_debt", nullable = false)
    private long pointsDebt;
    @Column(name = "official_revision", nullable = false)
    private long officialRevision;
    @Column(name = "official_synced_at", nullable = false)
    private Instant officialSyncedAt;
    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;
    @Column(name = "applied_at")
    private Instant appliedAt;
    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;
    @Column(name = "last_attempt_at")
    private Instant lastAttemptAt;
    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;
    @Version
    private long version;

    protected MemberPointsOfficialInbox() {
    }

    public MemberPointsOfficialInbox(
            UUID companyId,
            UUID storeId,
            UUID memberId,
            long points,
            long pointsDebt,
            long officialRevision,
            Instant officialSyncedAt,
            Instant receivedAt) {
        this.id = deterministicId(storeId, memberId);
        this.companyId = Objects.requireNonNull(companyId, "companyId");
        this.storeId = Objects.requireNonNull(storeId, "storeId");
        this.memberId = Objects.requireNonNull(memberId, "memberId");
        replace(points, pointsDebt, officialRevision, officialSyncedAt, receivedAt);
    }

    public void replace(
            long requestedPoints,
            long requestedPointsDebt,
            long requestedRevision,
            Instant requestedSyncedAt,
            Instant requestedReceivedAt) {
        if (requestedPoints < 0 || requestedPointsDebt < 0 || requestedRevision <= 0) {
            throw new IllegalArgumentException("Estado oficial de puntos invalido");
        }
        Objects.requireNonNull(requestedSyncedAt, "officialSyncedAt");
        Objects.requireNonNull(requestedReceivedAt, "receivedAt");
        if (officialRevision > requestedRevision) {
            return;
        }
        if (officialRevision == requestedRevision && officialRevision != 0) {
            if (points != requestedPoints || pointsDebt != requestedPointsDebt) {
                throw new IllegalStateException(
                        "Una revision oficial existente contiene valores diferentes");
            }
            return;
        }
        points = requestedPoints;
        pointsDebt = requestedPointsDebt;
        officialRevision = requestedRevision;
        officialSyncedAt = requestedSyncedAt;
        receivedAt = requestedReceivedAt;
        appliedAt = null;
        attemptCount = 0;
        lastAttemptAt = null;
        lastError = null;
    }

    public void markApplied(Instant now) {
        appliedAt = Objects.requireNonNull(now, "now");
        attemptCount = Math.addExact(attemptCount, 1);
        lastAttemptAt = now;
        lastError = null;
    }

    public void defer(Instant now, String error) {
        attemptCount = Math.addExact(attemptCount, 1);
        lastAttemptAt = Objects.requireNonNull(now, "now");
        lastError = error == null || error.isBlank()
                ? "Aplicacion pendiente"
                : error;
    }

    public void requireContext(UUID requestedCompanyId, UUID requestedStoreId) {
        if (!companyId.equals(requestedCompanyId) || !storeId.equals(requestedStoreId)) {
            throw new IllegalStateException(
                    "La bandeja oficial pertenece a otra empresa o tienda");
        }
    }

    public static UUID deterministicId(UUID storeId, UUID memberId) {
        Objects.requireNonNull(storeId, "storeId");
        Objects.requireNonNull(memberId, "memberId");
        return UUID.nameUUIDFromBytes((
                "MEMBER_POINTS_OFFICIAL_INBOX|" + storeId + "|" + memberId)
                .getBytes(StandardCharsets.UTF_8));
    }

    public UUID getStoreId() { return storeId; }
    public UUID getCompanyId() { return companyId; }
    public UUID getMemberId() { return memberId; }
    public long getPoints() { return points; }
    public long getPointsDebt() { return pointsDebt; }
    public long getOfficialRevision() { return officialRevision; }
    public Instant getOfficialSyncedAt() { return officialSyncedAt; }
    public Instant getAppliedAt() { return appliedAt; }
}
