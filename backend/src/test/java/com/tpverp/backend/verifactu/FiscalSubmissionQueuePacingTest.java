package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tpverp.backend.organization.CurrentOrganization;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FiscalSubmissionQueuePacingTest {

    private static final Instant NOW = Instant.parse("2026-08-27T12:00:00Z");

    private final FiscalSubmissionStateRepository states = mock(FiscalSubmissionStateRepository.class);
    private final FiscalRecordRepository records = mock(FiscalRecordRepository.class);
    private final FiscalSubmissionScopeFlowRepository flows = mock(FiscalSubmissionScopeFlowRepository.class);
    private final FiscalRecordArtifactRepository artifacts = mock(FiscalRecordArtifactRepository.class);
    private final UUID companyId = UUID.randomUUID();
    private final UUID installationId = UUID.randomUUID();
    private FiscalSubmissionQueueService queue;

    @BeforeEach
    void setUp() {
        queue = new FiscalSubmissionQueueService(
                states, records, mock(CurrentOrganization.class), Clock.fixed(NOW, ZoneOffset.UTC),
                new VerifactuDefectClassifier(), null, null, flows);
        queue.setArtifacts(artifacts);

        var scope = new FiscalSubmissionScopeFlow(
                companyId, installationId, FiscalEndpointEnvironment.TEST);
        scope.completed(NOW, 60);
        when(flows.findForUpdate(companyId, installationId, FiscalEndpointEnvironment.TEST))
                .thenReturn(Optional.of(scope));
    }

    @Test
    void allowsFullPacingBypassForBacklogAboveLimitAndClaimsExactlyOneThousand() {
        var selected = claimableStates(1000);
        stubSelectedBatch(selected);
        when(states.countClaimableBatch(companyId, installationId, "TEST", NOW))
                .thenReturn(1001L);

        var result = queue.claimBatch(
                companyId, installationId, FiscalEndpointEnvironment.TEST, 1000);

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().submissions()).hasSize(1000);
        verify(states).findClaimableBatch(companyId, installationId, "TEST", NOW, 1000);
    }

    @Test
    void smallerRequestNeverBypassesPacing() {
        var result = queue.claimBatch(
                companyId, installationId, FiscalEndpointEnvironment.TEST, 999);

        assertThat(result).isEmpty();
        verify(states, never()).countClaimableBatch(any(), any(), any(), any());
        verify(states, never()).findClaimableBatch(any(), any(), any(), any(), any(Integer.class));
    }

    private void stubSelectedBatch(List<FiscalSubmissionState> selected) {
        var selectedRecords = selected.stream().map(state -> {
            var record = mock(FiscalRecord.class);
            when(record.getId()).thenReturn(state.getRecordId());
            when(record.getFiscalMode()).thenReturn(FiscalMode.VERIFACTU);
            return record;
        }).toList();
        var selectedArtifacts = selected.stream().map(state -> {
            var artifact = mock(FiscalRecordArtifact.class);
            when(artifact.getRecordId()).thenReturn(state.getRecordId());
            when(artifact.getEnvironment()).thenReturn(FiscalEndpointEnvironment.TEST);
            return artifact;
        }).toList();
        when(states.findClaimableBatch(companyId, installationId, "TEST", NOW, 1000))
                .thenReturn(selected);
        when(records.findByCompanyIdAndInstallationIdAndIdInOrderBySequenceAsc(
                eq(companyId), eq(installationId), any())).thenReturn(selectedRecords);
        when(artifacts.findAllByRecordIdIn(any())).thenReturn(selectedArtifacts);
        when(states.save(any(FiscalSubmissionState.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private List<FiscalSubmissionState> claimableStates(int count) {
        return IntStream.range(0, count)
                .mapToObj(index -> new FiscalSubmissionState(
                        UUID.randomUUID(), FiscalSubmissionStatus.PENDIENTE, NOW))
                .toList();
    }
}
