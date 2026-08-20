package com.tpverp.saas.loyalty;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class MemberCategoryBootstrapApiModels {
    private MemberCategoryBootstrapApiModels() {
    }

    public record StoreRequest(UUID companyId, UUID storeId) {
    }

    public record BeginRequest(
            UUID companyId,
            UUID storeId,
            UUID snapshotId,
            int categoryChunkCount,
            int assignmentChunkCount,
            int categoryCount,
            int assignmentCount,
            String categoryHash,
            String assignmentHash,
            String snapshotChecksum) {
    }

    public record ChunkRequest(
            UUID companyId,
            UUID storeId,
            String chunkHash,
            List<CategoryValue> categories,
            List<AssignmentValue> assignments) {
    }

    public record CompleteRequest(
            UUID companyId,
            UUID storeId,
            String snapshotChecksum) {
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

    public record Status(
            UUID bootstrapId,
            UUID companyId,
            String status,
            List<UUID> expectedStoreIds,
            List<UUID> completedStoreIds,
            List<UUID> missingStoreIds,
            List<UUID> conflictStoreIds,
            String conflictReason,
            Long configRevision,
            Long assignmentRevision,
            Instant createdAt,
            Instant completedAt) {
    }
}
