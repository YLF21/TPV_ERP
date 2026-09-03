package com.tpverp.backend.management;

import java.util.List;
import java.util.UUID;

public record SafeRetirementResult(
        UUID id,
        RetirementOutcome outcome,
        List<String> reasonCodes) {

    public SafeRetirementResult {
        reasonCodes = List.copyOf(reasonCodes);
    }
}
