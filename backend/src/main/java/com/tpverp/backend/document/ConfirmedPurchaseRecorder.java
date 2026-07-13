package com.tpverp.backend.document;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.UUID;

@FunctionalInterface
public interface ConfirmedPurchaseRecorder {

    void record(UUID supplierId, Instant entryAt, Collection<PurchaseLine> lines);

    record PurchaseLine(
            UUID productId,
            String supplierReference,
            BigDecimal grossPurchasePrice,
            BigDecimal purchaseDiscount) {
    }
}
