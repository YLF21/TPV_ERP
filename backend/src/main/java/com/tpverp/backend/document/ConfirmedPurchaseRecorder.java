package com.tpverp.backend.document;

import java.time.LocalDate;
import java.util.Collection;
import java.util.UUID;

@FunctionalInterface
public interface ConfirmedPurchaseRecorder {

    void record(UUID supplierId, LocalDate date, Collection<UUID> productIds);
}
