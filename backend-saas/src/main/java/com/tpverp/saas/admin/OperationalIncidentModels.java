package com.tpverp.saas.admin;

import java.time.Instant;
import java.util.UUID;

public final class OperationalIncidentModels {
    private OperationalIncidentModels() {
    }

    public record Incident(
            String incidentType,
            UUID companyId,
            UUID targetId,
            String status,
            int expectedStoreCount,
            int completedStoreCount,
            int snapshotCount,
            int chunkCount,
            String conflictSummary,
            Instant createdAt,
            Instant lastActivityAt,
            boolean inactive,
            boolean cancellable,
            UUID completedBaselineId) {
    }

    public record CancelMemberCategoryBootstrapRequest(
            UUID commandId,
            String expectedStatus,
            String reason) {
    }

    public record CancellationResult(
            UUID commandId,
            UUID companyId,
            UUID bootstrapId,
            String previousStatus,
            String status,
            Instant cancelledAt,
            boolean idempotentReplay) {
    }
}
