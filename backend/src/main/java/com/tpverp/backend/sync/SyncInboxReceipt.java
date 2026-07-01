package com.tpverp.backend.sync;

import java.util.UUID;

public record SyncInboxReceipt(UUID eventId, SyncInboxResult result) {
}
