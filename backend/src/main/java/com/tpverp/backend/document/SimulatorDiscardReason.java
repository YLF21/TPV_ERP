package com.tpverp.backend.document;

import java.util.Set;

final class SimulatorDiscardReason {
    private static final Set<String> ALLOWED = Set.of("application_shutdown", "sale_entry_cleanup");

    private SimulatorDiscardReason() {
    }

    static String require(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("simulator_discard_reason_required");
        }
        var normalized = reason.trim();
        if (!ALLOWED.contains(normalized)) {
            throw new IllegalArgumentException("simulator_discard_reason_invalid");
        }
        return normalized;
    }

    static boolean isAllowed(String reason) {
        try {
            require(reason);
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
