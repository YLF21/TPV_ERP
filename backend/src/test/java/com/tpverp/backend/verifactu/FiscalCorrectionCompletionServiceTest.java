package com.tpverp.backend.verifactu;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FiscalCorrectionCompletionServiceTest {

    @Test
    void marksOriginalAsCorrectedWhenAeatAcceptsCorrection() {
        var relations = mock(FiscalRecordRelationRepository.class);
        var states = mock(FiscalSubmissionStateService.class);
        var correction = mock(FiscalRecord.class);
        var relation = mock(FiscalRecordRelation.class);
        var originalId = UUID.randomUUID();
        when(correction.getId()).thenReturn(UUID.randomUUID());
        when(relation.getRelatedId()).thenReturn(originalId);
        when(relations.findByRecordIdAndType(
                correction.getId(), FiscalRelationType.SUBSANA))
                .thenReturn(Optional.of(relation));

        new FiscalCorrectionCompletionService(relations, states).accepted(correction);

        verify(states).markSubsanado(originalId);
    }

    @Test
    void ignoresAcceptedOrdinaryRecord() {
        var relations = mock(FiscalRecordRelationRepository.class);
        var states = mock(FiscalSubmissionStateService.class);
        var record = mock(FiscalRecord.class);
        when(relations.findByRecordIdAndType(record.getId(), FiscalRelationType.SUBSANA))
                .thenReturn(Optional.empty());

        new FiscalCorrectionCompletionService(relations, states).accepted(record);

        verify(states, never()).markSubsanado(org.mockito.ArgumentMatchers.any());
    }
}
