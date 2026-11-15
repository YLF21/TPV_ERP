package com.tpverp.saas.sync;

import java.util.UUID;

public record SyncEventReceipt(UUID eventId, boolean accepted, SaasSyncEvent.ProjectionStatus projectionStatus) {
    public SyncEventReceipt(UUID eventId, boolean accepted) {
        this(eventId, accepted, null);
    }
}
