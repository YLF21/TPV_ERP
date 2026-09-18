package com.tpverp.backend.verifactu;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FiscalCorrectionCompletionService {

    private final FiscalRecordRelationRepository relations;
    private final FiscalSubmissionStateService states;

    public FiscalCorrectionCompletionService(
            FiscalRecordRelationRepository relations,
            FiscalSubmissionStateService states) {
        this.relations = relations;
        this.states = states;
    }

    // Cierra la incidencia original unicamente cuando AEAT acepta su subsanacion.
    @Transactional
    public void accepted(FiscalRecord record) {
        // Oldest ancestor first, matching the canonical correction lock order.
        // An empty scoped result is final: never fall back to an unscoped relation.
        relations.findSubsanationAncestorIds(record.getId()).forEach(states::markSubsanado);
    }
}
