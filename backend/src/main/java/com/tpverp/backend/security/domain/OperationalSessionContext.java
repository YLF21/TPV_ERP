package com.tpverp.backend.security.domain;

import java.util.Objects;
import java.util.UUID;

/** Identifica la sesión autenticada y, cuando existe, su alcance operativo de terminal. */
public record OperationalSessionContext(UUID sessionId, UUID terminalId, UUID storeId) {

    public OperationalSessionContext {
        if ((terminalId == null) != (storeId == null)) {
            throw new IllegalArgumentException("terminalId y storeId deben estar ambos presentes o ausentes");
        }
    }

    public OperationalSessionContext(UUID terminalId, UUID storeId) {
        this(null, Objects.requireNonNull(terminalId, "terminalId"),
                Objects.requireNonNull(storeId, "storeId"));
    }

    public boolean isOperational() {
        return terminalId != null;
    }
}
