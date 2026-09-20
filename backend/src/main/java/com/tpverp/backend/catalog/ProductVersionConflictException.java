package com.tpverp.backend.catalog;

import java.util.UUID;

/** Keeps catalog concurrency details available to the calling workflow. */
public final class ProductVersionConflictException extends IllegalStateException {
    private final UUID productId;
    private final long expectedVersion;
    private final long actualVersion;

    public ProductVersionConflictException(UUID productId, long expectedVersion, long actualVersion) {
        super("Conflicto de version en el producto " + productId + ": se esperaba "
                + expectedVersion + " y tiene version " + actualVersion);
        this.productId = productId;
        this.expectedVersion = expectedVersion;
        this.actualVersion = actualVersion;
    }

    public UUID productId() { return productId; }
    public long expectedVersion() { return expectedVersion; }
    public long actualVersion() { return actualVersion; }
}
