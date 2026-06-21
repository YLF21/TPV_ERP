package com.tpverp.backend.verifactu;

import java.util.Objects;
import java.util.UUID;

public record FiscalRecordQueuedEvent(UUID recordId) {

    public FiscalRecordQueuedEvent {
        Objects.requireNonNull(recordId, "recordId");
    }
}
