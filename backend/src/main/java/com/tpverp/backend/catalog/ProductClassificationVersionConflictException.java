package com.tpverp.backend.catalog;

import java.util.List;
import java.util.UUID;

/** Reports every stale product in one atomic classification request. */
public final class ProductClassificationVersionConflictException extends IllegalStateException {

    public record Conflict(UUID productId, long expectedVersion, long actualVersion) {
        /** Compatibility accessor used by persistence/contract clients. */
        public long currentVersion() {
            return actualVersion;
        }
    }

    private final List<Conflict> conflicts;

    public ProductClassificationVersionConflictException(List<Conflict> conflicts) {
        super("conflicto_version_producto: hay productos modificados; recarga la lista e intenta de nuevo");
        if (conflicts == null || conflicts.isEmpty()) {
            throw new IllegalArgumentException("Debe existir al menos un conflicto de version");
        }
        this.conflicts = List.copyOf(conflicts);
    }

    public List<Conflict> conflicts() {
        return conflicts;
    }
}
