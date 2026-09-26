package com.tpverp.saas.supervision;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class StoreRepairModels {
    private StoreRepairModels() { }
    public record CreateRepairRequest(@NotNull UUID requestId, @NotBlank @Size(min = 5, max = 500) String reason) { }
    public record ManualRequest(@NotBlank @Size(min = 5, max = 500) String reason) { }
    public record ManualResponse(UUID ticketId) { }
    public record ClaimRequest(@NotNull UUID installationId) { }
    public record ResultRequest(@NotNull UUID installationId, @NotBlank String status, @NotBlank String resultCode) { }
    public record RepairCommandView(UUID commandId, UUID requestId, String action, UUID companyId, UUID storeId, UUID eventId,
            long expectedVersion, String status, String resultCode, String requestedBy, String reason,
            Instant createdAt, Instant expiresAt, Instant updatedAt) { }
    public record ClaimedCommand(UUID commandId, String action, UUID companyId, UUID storeId, UUID eventId,
            long expectedVersion, Instant expiresAt) { }
    public record RepairState(boolean remoteEligible, String ineligibleReason, List<RepairCommandView> commands, UUID manualTicketId) { }
}
