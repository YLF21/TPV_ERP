package com.tpverp.backend.document;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/** Scope captured when a control event is queued; it never selects the authenticated identity. */
public record SaleLineDeletionContext(
        @NotNull UUID storeId,
        @NotNull UUID userId,
        @NotNull UUID terminalId) {
}
