package com.tpverp.backend.verifactu;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FiscalCorrectionCompletionServiceTest {

    @Test
    void marksOriginalAsCorrectedWhenAeatAcceptsCorrection() {
        var relations = mock(FiscalRecordRelationRepository.class);
        var states = mock(FiscalSubmissionStateService.class);
        var correction = mock(FiscalRecord.class);
        var originalId = UUID.randomUUID();
        when(correction.getId()).thenReturn(UUID.randomUUID());
        when(relations.findSubsanationAncestorIds(correction.getId())).thenReturn(List.of(originalId));

        new FiscalCorrectionCompletionService(relations, states).accepted(correction);

        verify(states).markSubsanado(originalId);
    }

    @Test
    void ignoresAcceptedOrdinaryRecord() {
        var relations = mock(FiscalRecordRelationRepository.class);
        var states = mock(FiscalSubmissionStateService.class);
        var record = mock(FiscalRecord.class);
        when(relations.findSubsanationAncestorIds(record.getId())).thenReturn(List.of());

        new FiscalCorrectionCompletionService(relations, states).accepted(record);

        verify(states, never()).markSubsanado(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void marksAllSubsanationAncestorsInOrder() {
        var relations = mock(FiscalRecordRelationRepository.class);
        var states = mock(FiscalSubmissionStateService.class);
        var correction = mock(FiscalRecord.class);
        var first = UUID.randomUUID();
        var second = UUID.randomUUID();
        when(correction.getId()).thenReturn(UUID.randomUUID());
        when(relations.findSubsanationAncestorIds(correction.getId()))
                .thenReturn(List.of(first, second));

        new FiscalCorrectionCompletionService(relations, states).accepted(correction);

        var order = org.mockito.Mockito.inOrder(states);
        order.verify(states).markSubsanado(first);
        order.verify(states).markSubsanado(second);
    }

    @Test
    void doesNotMarkASeparateChainWhenRecursiveScopeReturnsNone() {
        var relations = mock(FiscalRecordRelationRepository.class);
        var states = mock(FiscalSubmissionStateService.class);
        var correction = mock(FiscalRecord.class);
        when(correction.getId()).thenReturn(UUID.randomUUID());
        when(relations.findSubsanationAncestorIds(correction.getId())).thenReturn(List.of());

        new FiscalCorrectionCompletionService(relations, states).accepted(correction);

        verify(states, never()).markSubsanado(org.mockito.ArgumentMatchers.any());
    }
}
