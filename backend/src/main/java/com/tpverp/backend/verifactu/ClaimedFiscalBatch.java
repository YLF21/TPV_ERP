package com.tpverp.backend.verifactu;

import java.util.List;
import java.util.UUID;

public record ClaimedFiscalBatch(
        FiscalSubmissionScopeFlow scope,
        List<ClaimedFiscalSubmission> submissions,
        UUID batchId) {
    public ClaimedFiscalBatch(
            FiscalSubmissionScopeFlow scope,
            List<ClaimedFiscalSubmission> submissions) {
        this(scope, submissions, UUID.randomUUID());
    }

    public ClaimedFiscalBatch {
        if (scope == null) throw new IllegalArgumentException("scope fiscal obligatorio");
        if (submissions == null || submissions.isEmpty()) {
            throw new IllegalArgumentException("El lote fiscal debe contener lineas");
        }
        if (batchId == null) throw new IllegalArgumentException("identidad de lote obligatoria");
        submissions = List.copyOf(submissions);
    }
}
