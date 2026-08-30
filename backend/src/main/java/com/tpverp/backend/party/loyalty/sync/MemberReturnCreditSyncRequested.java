package com.tpverp.backend.party.loyalty.sync;

import java.util.Objects;
import java.util.UUID;

/**
 * Post-commit priority wake for a newly issued return-credit lot. The durable
 * outbox remains the source of truth when the central endpoint is unavailable.
 */
public record MemberReturnCreditSyncRequested(UUID eventId) {
    public MemberReturnCreditSyncRequested {
        Objects.requireNonNull(eventId, "eventId");
    }
}
