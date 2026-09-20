package com.tpverp.backend.catalog;

import java.util.List;
import java.util.UUID;

/** Distinguishes a stale workspace from stale catalog products without discarding either snapshot. */
public final class ProductBulkEditConflictException extends IllegalStateException {
    public record ProductConflict(UUID productId, Long expectedVersion, long actualVersion) { }

    private final UUID draftId;
    private final Long expectedVersion;
    private final Long actualVersion;
    private final List<ProductConflict> conflicts;

    private ProductBulkEditConflictException(String message, UUID draftId, Long expectedVersion,
            Long actualVersion, List<ProductConflict> conflicts) {
        super(message);
        this.draftId = draftId;
        this.expectedVersion = expectedVersion;
        this.actualVersion = actualVersion;
        this.conflicts = List.copyOf(conflicts);
    }

    public static ProductBulkEditConflictException list(UUID draftId, long expected, Long actual) {
        String detail = actual == null ? "ya fue modificada" : "tiene version " + actual;
        return new ProductBulkEditConflictException("Conflicto de version en la lista " + draftId
                + ": se esperaba " + expected + " y " + detail, draftId, expected, actual, List.of());
    }

    public static ProductBulkEditConflictException product(UUID draftId, UUID productId, Long expected, long actual) {
        return new ProductBulkEditConflictException("Conflicto de version en el producto " + productId
                + ": se esperaba " + expected + " y tiene version " + actual, draftId, null, null,
                List.of(new ProductConflict(productId, expected, actual)));
    }

    public String code() {
        return conflicts.isEmpty() ? "BULK_EDIT_LIST_VERSION_CONFLICT" : "BULK_EDIT_PRODUCT_VERSION_CONFLICT";
    }
    public UUID draftId() { return draftId; }
    public Long expectedVersion() { return expectedVersion; }
    public Long actualVersion() { return actualVersion; }
    public List<ProductConflict> conflicts() { return conflicts; }
}
