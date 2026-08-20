package com.tpverp.backend.party.loyalty.points;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "member_points_projection_state")
public class MemberPointsProjectionState {
    @Id
    @Column(name = "tienda_id")
    private UUID storeId;
    @Column(name = "empresa_id", nullable = false)
    private UUID companyId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private MemberPointsProjectionStatus status;
    @Column(name = "last_sequence", nullable = false)
    private long lastSequence;
    @Column(name = "cut_sequence")
    private Long cutSequence;
    @Column(name = "projected_through_sequence", nullable = false)
    private long projectedThroughSequence;
    @Column(name = "official_through_sequence", nullable = false)
    private long officialThroughSequence;
    @Column(name = "official_revision", nullable = false)
    private long officialRevision;
    @Column(name = "bootstrap_id")
    private UUID bootstrapId;
    @Column(name = "snapshot_id")
    private UUID snapshotId;
    @Column(name = "cutoff_at")
    private Instant cutoffAt;
    @Version
    private long version;

    protected MemberPointsProjectionState() {
    }

    public MemberPointsProjectionState(UUID companyId, UUID storeId) {
        this.companyId = Objects.requireNonNull(companyId, "companyId");
        this.storeId = Objects.requireNonNull(storeId, "storeId");
        this.status = MemberPointsProjectionStatus.LOCAL_ACTIVE;
    }

    public long allocateSequence() {
        lastSequence = Math.addExact(lastSequence, 1);
        if (status == MemberPointsProjectionStatus.LOCAL_ACTIVE) {
            projectedThroughSequence = lastSequence;
        }
        return lastSequence;
    }

    public void freeze(
            UUID requestedBootstrapId,
            UUID requestedSnapshotId,
            Instant requestedCutoffAt) {
        Objects.requireNonNull(requestedBootstrapId, "bootstrapId");
        Objects.requireNonNull(requestedSnapshotId, "snapshotId");
        Objects.requireNonNull(requestedCutoffAt, "cutoffAt");
        if (status == MemberPointsProjectionStatus.FROZEN
                && requestedBootstrapId.equals(bootstrapId)
                && requestedSnapshotId.equals(snapshotId)
                && requestedCutoffAt.equals(cutoffAt)) {
            return;
        }
        if (status != MemberPointsProjectionStatus.LOCAL_ACTIVE) {
            throw new IllegalStateException(
                    "El estado de puntos no admite un nuevo corte: " + status);
        }
        status = MemberPointsProjectionStatus.FROZEN;
        cutSequence = lastSequence;
        bootstrapId = requestedBootstrapId;
        snapshotId = requestedSnapshotId;
        cutoffAt = requestedCutoffAt;
    }

    public void requireCompany(UUID requestedCompanyId) {
        if (!companyId.equals(requestedCompanyId)) {
            throw new IllegalStateException("La tienda no pertenece a la empresa del corte");
        }
    }

    public void activateCentral(long throughSequence, long revision) {
        if (status == MemberPointsProjectionStatus.LOCAL_ACTIVE
                || status == MemberPointsProjectionStatus.CONFLICT) {
            throw new IllegalStateException(
                    "El estado de puntos no admite activar la autoridad central: " + status);
        }
        if (throughSequence < 0 || revision < 0) {
            throw new IllegalArgumentException(
                    "La secuencia y revision oficiales no pueden ser negativas");
        }
        officialThroughSequence = Math.max(officialThroughSequence, throughSequence);
        officialRevision = Math.max(officialRevision, revision);
        status = MemberPointsProjectionStatus.CENTRAL_ACTIVE;
    }

    public void advanceOfficialRevision(long revision) {
        if (status != MemberPointsProjectionStatus.CENTRAL_ACTIVE) {
            throw new IllegalStateException(
                    "La revision oficial solo avanza con autoridad central activa");
        }
        if (revision < officialRevision) {
            throw new IllegalArgumentException("La revision oficial no puede retroceder");
        }
        officialRevision = revision;
    }

    public void waitForOfficialState() {
        if (status != MemberPointsProjectionStatus.FROZEN
                && status != MemberPointsProjectionStatus.CATCHING_UP
                && status != MemberPointsProjectionStatus.WAITING_OFFICIAL) {
            throw new IllegalStateException(
                    "El estado de puntos no puede esperar el snapshot oficial: " + status);
        }
        status = MemberPointsProjectionStatus.WAITING_OFFICIAL;
    }

    public void catchUp() {
        if (status != MemberPointsProjectionStatus.FROZEN
                && status != MemberPointsProjectionStatus.WAITING_OFFICIAL
                && status != MemberPointsProjectionStatus.CATCHING_UP) {
            throw new IllegalStateException(
                    "El estado de puntos no admite catch-up: " + status);
        }
        status = MemberPointsProjectionStatus.CATCHING_UP;
    }

    public void conflict() {
        if (status != MemberPointsProjectionStatus.CENTRAL_ACTIVE) {
            status = MemberPointsProjectionStatus.CONFLICT;
        }
    }

    public UUID getStoreId() { return storeId; }
    public UUID getCompanyId() { return companyId; }
    public MemberPointsProjectionStatus getStatus() { return status; }
    public long getLastSequence() { return lastSequence; }
    public Long getCutSequence() { return cutSequence; }
    public long getProjectedThroughSequence() { return projectedThroughSequence; }
    public long getOfficialThroughSequence() { return officialThroughSequence; }
    public long getOfficialRevision() { return officialRevision; }
    public UUID getBootstrapId() { return bootstrapId; }
    public UUID getSnapshotId() { return snapshotId; }
    public Instant getCutoffAt() { return cutoffAt; }
    public long getVersion() { return version; }
}
