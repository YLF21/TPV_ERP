package com.tpverp.backend.security.domain;

import java.util.Objects;
import java.util.UUID;

/** Identifica el alcance operativo validado al autenticar una sesión mediante terminal. */
public record OperationalSessionContext(UUID terminalId, UUID storeId) {

    public OperationalSessionContext {
        Objects.requireNonNull(terminalId, "terminalId");
        Objects.requireNonNull(storeId, "storeId");
    }
}
