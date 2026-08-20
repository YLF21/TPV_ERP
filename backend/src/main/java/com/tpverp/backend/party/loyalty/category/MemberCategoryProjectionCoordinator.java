package com.tpverp.backend.party.loyalty.category;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MemberCategoryProjectionCoordinator {
    private final MemberCategoryProjectionStateRepository states;
    private final Clock clock;

    public MemberCategoryProjectionCoordinator(
            MemberCategoryProjectionStateRepository states,
            Clock clock) {
        this.states = states;
        this.clock = clock;
    }

    @Transactional
    public FreezeResult freeze(
            UUID companyId,
            UUID storeId,
            UUID bootstrapId,
            UUID snapshotId) {
        Objects.requireNonNull(companyId, "companyId");
        Objects.requireNonNull(storeId, "storeId");
        states.insertIfMissing(companyId, storeId);
        var state = states.findForUpdate(storeId)
                .orElseThrow(() -> new IllegalStateException(
                        "No se pudo inicializar el estado local de categorias"));
        state.requireCompany(companyId);
        state.freeze(bootstrapId, snapshotId, clock.instant());
        return new FreezeResult(
                companyId,
                storeId,
                state.getBootstrapId(),
                state.getSnapshotId(),
                state.getFrozenAt());
    }

    @Transactional
    public void markConflict(UUID companyId, UUID storeId, UUID bootstrapId, UUID snapshotId) {
        states.insertIfMissing(companyId, storeId);
        var state = states.findForUpdate(storeId)
                .orElseThrow(() -> new IllegalStateException(
                        "No se pudo cargar el estado local de categorias"));
        state.requireCompany(companyId);
        state.markConflict(bootstrapId, snapshotId);
    }

    @Transactional
    public void activateCentral(
            UUID companyId,
            UUID storeId,
            UUID bootstrapId,
            UUID snapshotId,
            long configRevision,
            long assignmentRevision) {
        states.insertIfMissing(companyId, storeId);
        var state = states.findForUpdate(storeId)
                .orElseThrow(() -> new IllegalStateException(
                        "No se pudo cargar el estado local de categorias"));
        state.requireCompany(companyId);
        state.activateCentral(
                bootstrapId,
                snapshotId,
                configRevision,
                assignmentRevision);
    }

    @Transactional
    public void advanceOfficialFeed(
            UUID companyId,
            UUID storeId,
            long expectedConfigRevision,
            UUID expectedConfigId,
            long nextConfigRevision,
            UUID nextConfigId,
            long expectedAssignmentRevision,
            UUID expectedAssignmentId,
            long nextAssignmentRevision,
            UUID nextAssignmentId) {
        var state = states.findForUpdate(storeId)
                .orElseThrow(() -> new IllegalStateException(
                        "No existe la proyeccion local de categorias"));
        state.requireCompany(companyId);
        state.advanceOfficialFeed(
                expectedConfigRevision,
                expectedConfigId,
                nextConfigRevision,
                nextConfigId,
                expectedAssignmentRevision,
                expectedAssignmentId,
                nextAssignmentRevision,
                nextAssignmentId);
    }

    public record FreezeResult(
            UUID companyId,
            UUID storeId,
            UUID bootstrapId,
            UUID snapshotId,
            Instant frozenAt) {
    }
}
