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
        relations.findByRecordIdAndType(record.getId(), FiscalRelationType.SUBSANA)
                .ifPresent(relation -> states.markSubsanado(relation.getRelatedId()));
    }
}
