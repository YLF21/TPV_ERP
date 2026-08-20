package com.tpverp.backend.party.loyalty.points;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MemberPointsProjectionCoordinator {
    private final MemberPointsProjectionStateRepository states;

    public MemberPointsProjectionCoordinator(
            MemberPointsProjectionStateRepository states) {
        this.states = states;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public ProjectionDecision allocate(UUID companyId, UUID storeId) {
        var state = lockedState(companyId, storeId);
        var sequence = state.allocateSequence();
        return new ProjectionDecision(
                sequence,
                state.getStatus() == MemberPointsProjectionStatus.LOCAL_ACTIVE,
                state.getStatus());
    }

    @Transactional
    public FreezeResult freeze(
            UUID companyId,
            UUID storeId,
            UUID bootstrapId,
            UUID snapshotId,
            Instant cutoffAt) {
        var state = lockedState(companyId, storeId);
        state.freeze(bootstrapId, snapshotId, cutoffAt);
        return FreezeResult.from(state);
    }

    @Transactional
    public void activateCentral(
            UUID companyId,
            UUID storeId,
            long officialThroughSequence,
            long officialRevision) {
        lockedState(companyId, storeId)
                .activateCentral(officialThroughSequence, officialRevision);
    }

    @Transactional
    public void advanceOfficialRevision(
            UUID companyId,
            UUID storeId,
            long officialRevision) {
        lockedState(companyId, storeId).advanceOfficialRevision(officialRevision);
    }

    private MemberPointsProjectionState lockedState(UUID companyId, UUID storeId) {
        Objects.requireNonNull(companyId, "companyId");
        Objects.requireNonNull(storeId, "storeId");
        states.insertIfMissing(companyId, storeId);
        var state = states.findLockedByStoreId(storeId)
                .orElseThrow(() -> new IllegalStateException(
                        "No se pudo inicializar el estado local de puntos"));
        state.requireCompany(companyId);
        return state;
    }

    public record ProjectionDecision(
            long storeSequence,
            boolean projectLocally,
            MemberPointsProjectionStatus status) {
        public ProjectionDecision(long storeSequence, boolean projectLocally) {
            this(
                    storeSequence,
                    projectLocally,
                    projectLocally
                            ? MemberPointsProjectionStatus.LOCAL_ACTIVE
                            : MemberPointsProjectionStatus.FROZEN);
        }

        public ProjectionDecision {
            if (storeSequence <= 0) {
                throw new IllegalArgumentException("storeSequence debe ser positiva");
            }
            Objects.requireNonNull(status, "status");
        }
    }

    public record FreezeResult(
            UUID companyId,
            UUID storeId,
            UUID bootstrapId,
            UUID snapshotId,
            Instant cutoffAt,
            long cutSequence) {
        private static FreezeResult from(MemberPointsProjectionState state) {
            return new FreezeResult(
                    state.getCompanyId(), state.getStoreId(),
                    state.getBootstrapId(), state.getSnapshotId(),
                    state.getCutoffAt(),
                    Objects.requireNonNull(state.getCutSequence(), "cutSequence"));
        }
    }
}
