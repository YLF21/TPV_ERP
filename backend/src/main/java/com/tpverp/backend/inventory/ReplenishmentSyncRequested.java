package com.tpverp.backend.inventory;

import java.util.UUID;

/** A directed wake; the event itself is durably stored in the same business transaction. */
public record ReplenishmentSyncRequested(UUID eventId) { }
