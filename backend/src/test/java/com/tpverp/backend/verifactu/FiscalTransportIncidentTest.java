package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FiscalTransportIncidentTest {
    private static final Instant NOW = Instant.parse("2026-09-18T10:00:00Z");

    @Test
    void failureOpensDurableScopeIncidentAndRetryKeepsItsOriginalStart() {
        var fixture = new Fixture();
        fixture.service.recordTransportFailure(fixture.batch, "NETWORK_ERROR", "Timeout", "request");
        assertThat(fixture.scope.getIncidentSince()).isEqualTo(NOW);
        assertThat(fixture.scope.getLeaseOwner()).isNull();
        fixture.scope.markTransportIncident(NOW.plusSeconds(600));
        assertThat(fixture.scope.getIncidentSince()).isEqualTo(NOW);
        verify(fixture.attempts).recordTransportFailure(any(), any(), any(), any(), any(), any(), any());
        verify(fixture.flows).save(fixture.scope);
    }

    @Test
    void partialDrainKeepsIncidentButFinalAckClosesIt() {
        var partial = new Fixture();
        partial.scope.markTransportIncident(NOW.minusSeconds(600));
        when(partial.states.hasUnsubmittedInScope(partial.scope.getCompanyId(),
                partial.scope.getInstallationId(), "TEST")).thenReturn(true);
        partial.service.recordResponse(partial.batch, partial.accepted());
        assertThat(partial.scope.hasTransportIncident()).isTrue();
        verify(partial.states).flush();

        var finalAck = new Fixture();
        finalAck.scope.markTransportIncident(NOW.minusSeconds(600));
        finalAck.service.recordResponse(finalAck.batch, finalAck.accepted());
        assertThat(finalAck.scope.hasTransportIncident()).isFalse();
        var order = inOrder(finalAck.attempts, finalAck.states, finalAck.flows);
        order.verify(finalAck.attempts).recordAccepted(any(), any(), any(), any());
        order.verify(finalAck.states).flush();
        order.verify(finalAck.states).hasUnsubmittedInScope(any(), any(), any());
        order.verify(finalAck.flows).save(finalAck.scope);
    }

    @Test
    void localInfrastructureFailureAlsoOpensIncidentWithoutTouchingFiscalRecord() {
        var fixture = new Fixture();
        fixture.service.releaseBeforeNetwork(fixture.batch, "PRE_NETWORK_INFRASTRUCTURE_FAILED", "Unavailable");
        assertThat(fixture.scope.hasTransportIncident()).isTrue();
        assertThat(fixture.state.getStatus()).isEqualTo(FiscalSubmissionStatus.ENVIADO);
        assertThat(fixture.state.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(60));
        verify(fixture.states).save(fixture.state);
    }

    @Test
    void expiredWorkerCannotClearIncidentOrApplyAck() {
        var fixture = new Fixture();
        fixture.scope.markTransportIncident(NOW.minusSeconds(600));
        var expiredWorker = new VerifactuBatchPersistenceService(fixture.flows, fixture.states,
                fixture.attempts, mock(FiscalCorrectionCompletionService.class),
                Clock.fixed(NOW.plusSeconds(121), ZoneOffset.UTC));

        assertThatThrownBy(() -> expiredWorker.recordResponse(fixture.batch, fixture.accepted()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("worker");
        assertThat(fixture.scope.getIncidentSince()).isEqualTo(NOW.minusSeconds(600));
        verifyNoInteractions(fixture.attempts);
        verify(fixture.flows, never()).save(any());
    }

    private static final class Fixture {
        final FiscalSubmissionScopeFlowRepository flows = mock(FiscalSubmissionScopeFlowRepository.class);
        final FiscalSubmissionStateRepository states = mock(FiscalSubmissionStateRepository.class);
        final FiscalSubmissionAttemptService attempts = mock(FiscalSubmissionAttemptService.class);
        final FiscalSubmissionScopeFlow scope = new FiscalSubmissionScopeFlow(
                UUID.randomUUID(), UUID.randomUUID(), FiscalEndpointEnvironment.TEST);
        final FiscalRecord record = mock(FiscalRecord.class);
        final FiscalSubmissionState state = new FiscalSubmissionState(UUID.randomUUID(), FiscalSubmissionStatus.PENDIENTE, NOW);
        final ClaimedFiscalBatch batch;
        final VerifactuBatchPersistenceService service;

        Fixture() {
            var owner = UUID.randomUUID();
            scope.claim(owner, NOW, NOW.plusSeconds(120));
            state.claim(owner, UUID.randomUUID(), NOW, NOW.plusSeconds(120));
            when(record.getId()).thenReturn(state.getRecordId());
            when(flows.findForUpdate(scope.getCompanyId(), scope.getInstallationId(), scope.getEnvironment()))
                    .thenReturn(Optional.of(scope));
            when(states.findForUpdate(state.getRecordId())).thenReturn(Optional.of(state));
            batch = new ClaimedFiscalBatch(scope, List.of(new ClaimedFiscalSubmission(record, state)));
            service = new VerifactuBatchPersistenceService(flows, states, attempts,
                    mock(FiscalCorrectionCompletionService.class), Clock.fixed(NOW, ZoneOffset.UTC));
        }

        VerifactuBatchResponse accepted() {
            return new VerifactuBatchResponse(FiscalSubmissionStatus.ACEPTADO, 60,
                    Map.of(state.getRecordId(), new VerifactuBatchResponse.Line(state.getRecordId(),
                            FiscalSubmissionStatus.ACEPTADO, null, null)), null, null, "ack", false);
        }
    }
}
