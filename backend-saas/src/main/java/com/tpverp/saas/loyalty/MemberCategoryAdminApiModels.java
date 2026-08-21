package com.tpverp.saas.loyalty;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public final class MemberCategoryAdminApiModels {
    private MemberCategoryAdminApiModels() {
    }

    public record CategoryCommand(
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

    public record AssignmentCommand(
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

    public record CommandResult(
            UUID commandId,
            String operation,
            UUID targetId,
            Long configRevision,
            Long assignmentRevision,
            Instant acceptedAt) {
    }
}
