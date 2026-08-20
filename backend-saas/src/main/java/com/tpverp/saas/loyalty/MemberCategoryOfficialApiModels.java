package com.tpverp.saas.loyalty;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class MemberCategoryOfficialApiModels {
    private MemberCategoryOfficialApiModels() {
    }

    public record StoreRequest(UUID companyId, UUID storeId) {
    }

    public record CategoryValue(
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

    public record AssignmentValue(
            UUID memberId,
            UUID categoryId,
            boolean lockAutomatic,
            boolean lockKnown,
            Instant assignedAt,
            String assignmentSource,
            String assignmentAction) {
    }

    public record SnapshotResponse(
            UUID companyId,
            long configRevision,
            long assignmentRevision,
            List<CategoryValue> categories,
            List<AssignmentValue> assignments,
            String categoryHash,
            String assignmentHash,
            String snapshotChecksum) {
    }

    public record FeedRequest(
            UUID companyId,
            UUID storeId,
            long afterConfigRevision,
            UUID afterConfigId,
            long afterAssignmentRevision,
            UUID afterAssignmentId,
            int limit) {
    }

    public record CategoryChange(long revision, CategoryValue value) {
    }

    public record AssignmentChange(long revision, AssignmentValue value) {
    }

    public record FeedResponse(
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
    }
}
