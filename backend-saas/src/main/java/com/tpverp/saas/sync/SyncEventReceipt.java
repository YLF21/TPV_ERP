package com.tpverp.saas.sync;

import java.util.UUID;

public record SyncEventReceipt(UUID eventId, boolean accepted) {
}
