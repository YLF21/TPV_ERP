package com.tpverp.backend.party.loyalty.category;

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
@Table(name = "member_category_projection_state")
public class MemberCategoryProjectionState {
    @Id
    @Column(name = "tienda_id")
    private UUID storeId;
    @Column(name = "empresa_id", nullable = false)
    private UUID companyId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private MemberCategoryProjectionStatus status;
    @Column(name = "bootstrap_id")
    private UUID bootstrapId;
    @Column(name = "snapshot_id")
    private UUID snapshotId;
    @Column(name = "config_revision", nullable = false)
    private long configRevision;
    @Column(name = "assignment_revision", nullable = false)
    private long assignmentRevision;
    @Column(name = "config_cursor_id")
    private UUID configCursorId;
    @Column(name = "assignment_cursor_id")
    private UUID assignmentCursorId;
    @Column(name = "frozen_at")
    private Instant frozenAt;
    @Version
    private long version;

    protected MemberCategoryProjectionState() {
    }

    public MemberCategoryProjectionState(UUID companyId, UUID storeId) {
        this.companyId = Objects.requireNonNull(companyId, "companyId");
        this.storeId = Objects.requireNonNull(storeId, "storeId");
        this.status = MemberCategoryProjectionStatus.LOCAL_ACTIVE;
    }

    public void freeze(UUID requestedBootstrapId, UUID requestedSnapshotId, Instant now) {
        Objects.requireNonNull(requestedBootstrapId, "bootstrapId");
        Objects.requireNonNull(requestedSnapshotId, "snapshotId");
        Objects.requireNonNull(now, "now");
        if (status == MemberCategoryProjectionStatus.FROZEN
                && requestedBootstrapId.equals(bootstrapId)
                && requestedSnapshotId.equals(snapshotId)) {
            return;
        }
        if (status != MemberCategoryProjectionStatus.LOCAL_ACTIVE) {
            throw new IllegalStateException(
                    "Las categorias ya tienen otra centralizacion en curso");
        }
        status = MemberCategoryProjectionStatus.FROZEN;
        bootstrapId = requestedBootstrapId;
        snapshotId = requestedSnapshotId;
        frozenAt = now;
    }

    public void requireCompany(UUID requestedCompanyId) {
        if (!companyId.equals(requestedCompanyId)) {
            throw new IllegalStateException("La tienda pertenece a otra empresa");
        }
    }

    public void markConflict(UUID requestedBootstrapId, UUID requestedSnapshotId) {
        Objects.requireNonNull(requestedBootstrapId, "bootstrapId");
        Objects.requireNonNull(requestedSnapshotId, "snapshotId");
        if (status == MemberCategoryProjectionStatus.CONFLICT
                && requestedBootstrapId.equals(bootstrapId)
                && requestedSnapshotId.equals(snapshotId)) {
            return;
        }
        if (status != MemberCategoryProjectionStatus.FROZEN
                || !requestedBootstrapId.equals(bootstrapId)
                || !requestedSnapshotId.equals(snapshotId)) {
            throw new IllegalStateException(
                    "El conflicto no pertenece al bootstrap local congelado");
        }
        status = MemberCategoryProjectionStatus.CONFLICT;
    }

    public void activateCentral(
            UUID requestedBootstrapId,
            UUID requestedSnapshotId,
            long requestedConfigRevision,
            long requestedAssignmentRevision) {
        Objects.requireNonNull(requestedBootstrapId, "bootstrapId");
        Objects.requireNonNull(requestedSnapshotId, "snapshotId");
        if (requestedConfigRevision <= 0 || requestedAssignmentRevision <= 0) {
            throw new IllegalArgumentException("Las revisiones oficiales deben ser positivas");
        }
        boolean sameBootstrap = requestedBootstrapId.equals(bootstrapId)
                && requestedSnapshotId.equals(snapshotId);
        if (status == MemberCategoryProjectionStatus.CENTRAL_ACTIVE && sameBootstrap) {
            if (requestedConfigRevision < configRevision
                    || requestedAssignmentRevision < assignmentRevision) {
                throw new IllegalStateException("El snapshot oficial es anterior al aplicado");
            }
            configRevision = requestedConfigRevision;
            assignmentRevision = requestedAssignmentRevision;
            return;
        }
        if (status != MemberCategoryProjectionStatus.FROZEN || !sameBootstrap) {
            throw new IllegalStateException(
                    "El snapshot oficial no pertenece al bootstrap local congelado");
        }
        configRevision = requestedConfigRevision;
        assignmentRevision = requestedAssignmentRevision;
        configCursorId = null;
        assignmentCursorId = null;
        status = MemberCategoryProjectionStatus.CENTRAL_ACTIVE;
    }

    public void advanceOfficialFeed(
            long expectedConfigRevision,
            UUID expectedConfigId,
            long nextConfigRevision,
            UUID nextConfigId,
            long expectedAssignmentRevision,
            UUID expectedAssignmentId,
            long nextAssignmentRevision,
            UUID nextAssignmentId) {
        if (status != MemberCategoryProjectionStatus.CENTRAL_ACTIVE) {
            throw new IllegalStateException("La autoridad central de categorias no esta activa");
        }
        if (configRevision != expectedConfigRevision
                || !Objects.equals(configCursorId, expectedConfigId)
                || assignmentRevision != expectedAssignmentRevision
                || !Objects.equals(assignmentCursorId, expectedAssignmentId)) {
            throw new IllegalStateException("El cursor local de categorias ha cambiado");
        }
        requireForward(configRevision, configCursorId, nextConfigRevision, nextConfigId);
        requireForward(
                assignmentRevision,
                assignmentCursorId,
                nextAssignmentRevision,
                nextAssignmentId);
        configRevision = nextConfigRevision;
        configCursorId = nextConfigId;
        assignmentRevision = nextAssignmentRevision;
        assignmentCursorId = nextAssignmentId;
    }

    private static void requireForward(
            long currentRevision,
            UUID currentId,
            long nextRevision,
            UUID nextId) {
        if (nextRevision < currentRevision
                || (nextRevision == currentRevision && currentId != null
                        && (nextId == null || nextId.compareTo(currentId) < 0))) {
            throw new IllegalStateException("El feed oficial intenta retroceder el cursor");
        }
    }

    public UUID getStoreId() { return storeId; }
    public UUID getCompanyId() { return companyId; }
    public MemberCategoryProjectionStatus getStatus() { return status; }
    public UUID getBootstrapId() { return bootstrapId; }
    public UUID getSnapshotId() { return snapshotId; }
    public long getConfigRevision() { return configRevision; }
    public long getAssignmentRevision() { return assignmentRevision; }
    public UUID getConfigCursorId() { return configCursorId; }
    public UUID getAssignmentCursorId() { return assignmentCursorId; }
    public Instant getFrozenAt() { return frozenAt; }
}
