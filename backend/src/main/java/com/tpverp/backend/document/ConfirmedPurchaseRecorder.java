package com.tpverp.backend.document;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

@FunctionalInterface
public interface ConfirmedPurchaseRecorder {

    void record(UUID supplierId, LocalDate date, Collection<UUID> productIds);

    default void recordWithReferences(
            UUID supplierId, LocalDate date, Map<UUID, String> referencesByProductId) {
        record(supplierId, date, referencesByProductId.keySet());
    }
}
