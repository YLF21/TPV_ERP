package com.tpverp.backend.supervision.repair;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record RemoteRepairCommand(UUID commandId, UUID companyId, UUID storeId, String action,
        UUID eventId, long expectedVersion, Instant expiresAt) {
    public RemoteRepairCommand {
        Objects.requireNonNull(commandId); Objects.requireNonNull(companyId); Objects.requireNonNull(storeId);
        Objects.requireNonNull(eventId); Objects.requireNonNull(expiresAt);
        if (action == null || !action.matches("[A-Z][A-Z0-9_]{0,63}") || expectedVersion < 0) {
            throw new IllegalArgumentException("REMOTE_REPAIR_INVALID_COMMAND");
        }
    }
}
