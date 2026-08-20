package com.tpverp.backend.party.loyalty.category;

import com.tpverp.backend.party.MemberCategoryRepository;
import com.tpverp.backend.party.MemberMovementRepository;
import com.tpverp.backend.party.MemberRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.Objects;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MemberCategoryBootstrapCaptureService {
    private final MemberCategoryProjectionCoordinator coordinator;
    private final MemberCategoryRepository categories;
    private final MemberRepository members;
    private final MemberMovementRepository movements;
    private final MemberCategoryBootstrapSnapshotRepository snapshots;
    private final MemberCategoryBootstrapCategoryRepository snapshotCategories;
    private final MemberCategoryBootstrapAssignmentRepository snapshotAssignments;
    private final Clock clock;

    public MemberCategoryBootstrapCaptureService(
            MemberCategoryProjectionCoordinator coordinator,
            MemberCategoryRepository categories,
            MemberRepository members,
            MemberMovementRepository movements,
            MemberCategoryBootstrapSnapshotRepository snapshots,
            MemberCategoryBootstrapCategoryRepository snapshotCategories,
            MemberCategoryBootstrapAssignmentRepository snapshotAssignments,
            Clock clock) {
        this.coordinator = coordinator;
        this.categories = categories;
        this.members = members;
        this.movements = movements;
        this.snapshots = snapshots;
        this.snapshotCategories = snapshotCategories;
        this.snapshotAssignments = snapshotAssignments;
        this.clock = clock;
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public CaptureResult freezeAndCapture(
            UUID companyId,
            UUID storeId,
            UUID bootstrapId,
            UUID snapshotId) {
        var freeze = coordinator.freeze(
                companyId, storeId, bootstrapId, snapshotId);
        var existing = snapshots.findById(snapshotId);
        if (existing.isPresent()) {
            return CaptureResult.from(existing.get());
        }
        if (snapshots.findByBootstrapIdAndStoreId(bootstrapId, storeId).isPresent()) {
            throw new IllegalStateException(
                    "El bootstrap ya tiene otro snapshot de categorias para la tienda");
        }

        var categoryValues = categories
                .findByCompanyIdOrderBySortOrderAscMinPointsAscNameAsc(companyId).stream()
                .sorted(java.util.Comparator
                        .comparing(com.tpverp.backend.party.MemberCategory::getCode)
                        .thenComparing(value -> value.getId().toString()))
                .toList();
        var latestMovements = new HashMap<UUID, List<com.tpverp.backend.party.MemberMovement>>();
        movements.findLatestCategoryMovements(companyId).forEach(value ->
                latestMovements.computeIfAbsent(
                        value.getMember().getId(), ignored -> new ArrayList<>()).add(value));
        var assignmentValues = new ArrayList<MemberCategoryBootstrapCanonicalizer.AssignmentValue>();
        for (var member : members.findByCompanyIdOrderByCustomerFiscalNameAsc(companyId)) {
            var candidates = latestMovements.get(member.getId());
            if (candidates != null && !candidates.isEmpty()) {
                var selected = candidates.get(0);
                boolean contradictory = candidates.stream().anyMatch(value ->
                        !Objects.equals(value.getNewCategoryId(), selected.getNewCategoryId())
                                || !Objects.equals(
                                        value.getCategoryLockAutomatic(),
                                        selected.getCategoryLockAutomatic()));
                if (contradictory) {
                    throw new IllegalStateException(
                            "Empate local contradictorio en la categoria del socio "
                                    + member.getId());
                }
                String action = selected.getCategoryAssignmentAction() != null
                        ? selected.getCategoryAssignmentAction()
                        : selected.getNewCategoryId() == null ? "CLEAR" : "SET";
                boolean sameAsCurrent = Objects.equals(
                        selected.getNewCategoryId(),
                        member.getMemberCategory() == null
                                ? null : member.getMemberCategory().getId());
                boolean lockKnown = "CLEAR".equals(action)
                        || selected.getCategoryLockAutomatic() != null
                        || sameAsCurrent;
                boolean lockAutomatic = "CLEAR".equals(action)
                        ? false
                        : selected.getCategoryLockAutomatic() != null
                                ? selected.getCategoryLockAutomatic()
                                : sameAsCurrent && member.isAutoCategoryLocked();
                assignmentValues.add(
                        new MemberCategoryBootstrapCanonicalizer.AssignmentValue(
                                member.getId(),
                                selected.getNewCategoryId(),
                                lockAutomatic,
                                lockKnown,
                                selected.getCreatedAt(),
                                "MOVEMENT",
                                action));
                continue;
            }
            if (member.getMemberCategory() != null
                    && (member.isAutoCategoryLocked()
                            || member.getMemberCategory().isManualOnly())) {
                assignmentValues.add(
                        new MemberCategoryBootstrapCanonicalizer.AssignmentValue(
                                member.getId(),
                                member.getMemberCategory().getId(),
                                member.isAutoCategoryLocked(),
                                true,
                                Instant.EPOCH,
                                "LEGACY_CURRENT",
                                "SET"));
            }
        }
        assignmentValues.sort(java.util.Comparator.comparing(
                value -> value.memberId().toString()));

        String categoryHash = MemberCategoryBootstrapCanonicalizer.hash(
                categoryValues.stream()
                        .map(MemberCategoryBootstrapCanonicalizer::categoryLine)
                        .toList());
        String assignmentHash = MemberCategoryBootstrapCanonicalizer.hash(
                assignmentValues.stream()
                        .map(MemberCategoryBootstrapCanonicalizer::assignmentLine)
                        .toList());
        String checksum = MemberCategoryBootstrapCanonicalizer.snapshotChecksum(
                categoryHash, assignmentHash);
        var snapshot = snapshots.saveAndFlush(new MemberCategoryBootstrapSnapshot(
                freeze.snapshotId(),
                freeze.bootstrapId(),
                freeze.companyId(),
                freeze.storeId(),
                categoryValues.size(),
                assignmentValues.size(),
                categoryHash,
                assignmentHash,
                checksum,
                clock.instant()));
        snapshotCategories.saveAll(categoryValues.stream()
                .map(value -> new MemberCategoryBootstrapCategory(snapshotId, value))
                .toList());
        snapshotAssignments.saveAll(assignmentValues.stream()
                .map(value -> new MemberCategoryBootstrapAssignment(
                        snapshotId,
                        value.memberId(),
                        value.categoryId(),
                        value.lockAutomatic(),
                        value.assignedAt(),
                        value.source(),
                        value.action(),
                        value.lockKnown()))
                .toList());
        return CaptureResult.from(snapshot);
    }

    public record CaptureResult(
            UUID snapshotId,
            UUID bootstrapId,
            UUID companyId,
            UUID storeId,
            int categoryCount,
            int assignmentCount,
            String categoryHash,
            String assignmentHash,
            String snapshotChecksum,
            Instant capturedAt) {
        static CaptureResult from(MemberCategoryBootstrapSnapshot snapshot) {
            return new CaptureResult(
                    snapshot.getSnapshotId(),
                    snapshot.getBootstrapId(),
                    snapshot.getCompanyId(),
                    snapshot.getStoreId(),
                    snapshot.getCategoryCount(),
                    snapshot.getAssignmentCount(),
                    snapshot.getCategoryHash(),
                    snapshot.getAssignmentHash(),
                    snapshot.getSnapshotChecksum(),
                    snapshot.getCapturedAt());
        }
    }
}
