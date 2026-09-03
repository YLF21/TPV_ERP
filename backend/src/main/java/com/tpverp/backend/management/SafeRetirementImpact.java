package com.tpverp.backend.management;

import java.util.List;
import java.util.UUID;

public record SafeRetirementImpact(
        UUID id,
        long version,
        String currentState,
        RetirementOutcome outcomeIfConfirmed,
        List<String> reasonCodes,
        boolean executable) {

    public SafeRetirementImpact {
        reasonCodes = List.copyOf(reasonCodes);
    }
}
