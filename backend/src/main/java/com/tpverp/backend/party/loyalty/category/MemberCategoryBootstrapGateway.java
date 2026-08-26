package com.tpverp.backend.party.loyalty.category;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface MemberCategoryBootstrapGateway {
    BootstrapStatus discover(UUID companyId, UUID storeId);

    BootstrapStatus status(UUID bootstrapId, UUID companyId, UUID storeId);

    BootstrapStatus begin(
            UUID bootstrapId,
            UUID companyId,
            UUID storeId,
            MemberCategoryBootstrapSnapshot snapshot,
            int categoryChunkCount,
            int assignmentChunkCount);

    BootstrapStatus uploadCategories(
            UUID bootstrapId,
            UUID snapshotId,
            UUID companyId,
            UUID storeId,
            int index,
            String chunkHash,
            List<CategoryValue> values);

    BootstrapStatus uploadAssignments(
            UUID bootstrapId,
            UUID snapshotId,
            UUID companyId,
            UUID storeId,
            int index,
            String chunkHash,
            List<AssignmentValue> values);

    BootstrapStatus complete(
            UUID bootstrapId,
            UUID snapshotId,
            UUID companyId,
            UUID storeId,
            String snapshotChecksum);

    OfficialSnapshot officialSnapshot(UUID companyId, UUID storeId);

    OfficialFeed officialFeed(
            UUID companyId,
            UUID storeId,
            long afterConfigRevision,
            UUID afterConfigId,
            long afterAssignmentRevision,
            UUID afterAssignmentId,
            int limit);

    AdminResult adminCategory(AdminCategoryCommand command);

    AdminResult adminAssignment(AdminAssignmentCommand command);

    record CategoryValue(
            UUID categoryId,
            String code,
            String name,
            long minPoints,
            BigDecimal discountPercent,
            boolean discountEnabled,
            boolean manualOnly,
            boolean active,
            int sortOrder) {
    }

    record AssignmentValue(
            UUID memberId,
            UUID categoryId,
            boolean lockAutomatic,
            boolean lockKnown,
            Instant assignedAt,
            String assignmentSource,
            String assignmentAction) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record BootstrapStatus(
            UUID bootstrapId,
            String status,
            String conflictReason,
            Long configRevision,
            Long assignmentRevision) {
        public boolean isCollecting() {
            return "COLLECTING".equals(status);
        }

        public boolean isCompleted() {
            return "COMPLETED".equals(status);
        }

        public boolean isConflict() {
            return "CONFLICT".equals(status);
        }
    }

    record OfficialSnapshot(
            UUID companyId,
            long configRevision,
            long assignmentRevision,
            List<CategoryValue> categories,
            List<AssignmentValue> assignments,
            String categoryHash,
            String assignmentHash,
            String snapshotChecksum) {
    }

    record CategoryChange(long revision, CategoryValue value) {
    }

    record AssignmentChange(long revision, AssignmentValue value) {
    }

    record OfficialFeed(
            UUID companyId,
            long fromConfigRevision,
            UUID fromConfigId,
            long nextConfigRevision,
            UUID nextConfigId,
            long fromAssignmentRevision,
            UUID fromAssignmentId,
            long nextAssignmentRevision,
            UUID nextAssignmentId,
            List<CategoryChange> categories,
            List<AssignmentChange> assignments,
            String categoryHash,
            String assignmentHash,
            String pageChecksum) {
        public boolean isEmpty() {
            return categories.isEmpty() && assignments.isEmpty();
        }
    }

    record AdminCategoryCommand(
            UUID commandId,
            UUID companyId,
            UUID storeId,
            UUID actorUserId,
            String actorName,
            String actorRole,
            UUID categoryId,
            String code,
            String name,
            long minPoints,
            BigDecimal discountPercent,
            boolean discountEnabled,
            boolean manualOnly,
            boolean active,
            int sortOrder,
            String reason) {
    }

    record AdminAssignmentCommand(
            UUID commandId,
            UUID companyId,
            UUID storeId,
            UUID actorUserId,
            String actorName,
            String actorRole,
            UUID memberId,
            UUID categoryId,
            boolean lockAutomatic,
            String action,
            String reason) {
    }

    record AdminResult(
            UUID commandId,
            String operation,
            UUID targetId,
            Long configRevision,
            Long assignmentRevision,
            Instant acceptedAt) {
    }
}
