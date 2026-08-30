package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class VerifactuBatchPersistenceServiceTest {

    @Test
    void ackTieneUnaFronteraTransaccional() throws Exception {
        var method = VerifactuBatchPersistenceService.class.getMethod(
                "recordResponse", ClaimedFiscalBatch.class, VerifactuBatchResponse.class);

        org.assertj.core.api.Assertions.assertThat(method.getAnnotation(Transactional.class)).isNotNull();
    }

    @Test
    void falloDelMarkerPropagaLaTransaccionCompletaDelAck() {
        var flows = Mockito.mock(FiscalSubmissionScopeFlowRepository.class);
        var states = Mockito.mock(FiscalSubmissionStateRepository.class);
        var attempts = Mockito.mock(FiscalSubmissionAttemptService.class);
        var corrections = Mockito.mock(FiscalCorrectionCompletionService.class);
        var evidences = Mockito.mock(FiscalSubmissionEvidenceRepository.class);
        var responses = Mockito.mock(FiscalSubmissionResponseEvidenceRepository.class);
        var marker = Mockito.mock(VerifactuFirstSubmissionMarker.class);
        var now = Instant.parse("2026-08-27T12:00:00Z");
        var scope = Mockito.mock(FiscalSubmissionScopeFlow.class);
        var state = Mockito.mock(FiscalSubmissionState.class);
        var record = Mockito.mock(FiscalRecord.class);
        var owner = UUID.randomUUID();
        var token = UUID.randomUUID();
        var companyId = UUID.randomUUID();
        var installationId = UUID.randomUUID();
        var evidenceId = UUID.randomUUID();
        var batch = new ClaimedFiscalBatch(scope,
                List.of(new ClaimedFiscalSubmission(record, state)), evidenceId);
        var evidence = Mockito.mock(FiscalSubmissionEvidence.class);
        var response = new VerifactuBatchResponse(
                FiscalSubmissionStatus.ACEPTADO, 30,
                Map.of(record.getId(), new VerifactuBatchResponse.Line(
                        record.getId(), FiscalSubmissionStatus.ACEPTADO, null, null)),
                null, null, "ack", false);

        when(scope.getCompanyId()).thenReturn(companyId);
        when(scope.getInstallationId()).thenReturn(installationId);
        when(scope.getEnvironment()).thenReturn(FiscalEndpointEnvironment.TEST);
        when(scope.getLeaseOwner()).thenReturn(owner);
        when(scope.isOwnedBy(any(), any())).thenReturn(true);
        when(state.getClaimToken()).thenReturn(token);
        when(state.getLeaseOwner()).thenReturn(owner);
        when(state.isOwnedBy(any(), any())).thenReturn(true);
        when(record.getId()).thenReturn(UUID.randomUUID());
        when(flows.findForUpdate(companyId, installationId, FiscalEndpointEnvironment.TEST))
                .thenReturn(Optional.of(scope));
        when(states.findForUpdate(record.getId())).thenReturn(Optional.of(state));
        when(evidences.findById(evidenceId)).thenReturn(Optional.of(evidence));
        when(responses.findByEvidenceId(evidenceId)).thenReturn(Optional.empty());
        doThrow(new IllegalStateException("marker roto")).when(marker).mark(record);

        var service = new VerifactuBatchPersistenceService(
                flows, states, attempts, corrections, evidences, responses, marker,
                Clock.fixed(now, ZoneOffset.UTC));

        assertThatThrownBy(() -> service.recordResponse(batch, response))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("marker roto");
        verify(marker).mark(record);
        verify(responses).save(any());
        verify(flows, never()).save(any());
    }
}
