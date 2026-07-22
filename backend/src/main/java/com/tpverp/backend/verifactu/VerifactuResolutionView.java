package com.tpverp.backend.verifactu;

import java.util.List;
import java.util.UUID;

public record VerifactuResolutionView(
        UUID recordId,
        FiscalRecordOperation operation,
        FiscalSubmissionStatus status,
        long version,
        String errorCode,
        VerifactuResolutionCategory category,
        VerifactuResolutionAction recommendedAction,
        List<VerifactuResolutionAction> permittedActions) {
}
