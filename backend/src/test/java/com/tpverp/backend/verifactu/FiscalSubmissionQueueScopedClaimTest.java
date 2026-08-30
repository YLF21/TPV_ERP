package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.junit.jupiter.api.Test;

class FiscalSubmissionQueueScopedClaimTest {

    private static final Instant NOW = Instant.parse("2026-08-27T12:00:00Z");

    @Test
    void explicitRecordCannotOvertakeUnacceptedPredecessor() {
        var companyId = UUID.randomUUID();
        var installationId = UUID.randomUUID();
        var predecessorId = UUID.randomUUID();
        var targetId = UUID.randomUUID();
        var states = mock(FiscalSubmissionStateRepository.class);
        var records = mock(FiscalRecordRepository.class);
        var target = mock(FiscalRecord.class);
        var predecessor = new FiscalSubmissionState(
                predecessorId, FiscalSubmissionStatus.PENDIENTE, NOW);
        when(records.findByIdAndCompanyIdAndInstallationId(targetId, companyId, installationId))
                .thenReturn(Optional.of(target));
        when(target.getFiscalMode()).thenReturn(FiscalMode.VERIFACTU);
        when(states.findPendingClaimableForScope(companyId, installationId, NOW, 1))
                .thenReturn(List.of(predecessor));

        var queue = new FiscalSubmissionQueueService(
                states, records, mock(CurrentOrganization.class),
                Clock.fixed(NOW, ZoneOffset.UTC), new VerifactuDefectClassifier());

        assertThat(queue.claimPendingForScope(companyId, installationId, targetId)).isEmpty();
        verify(states, never()).findForUpdate(targetId);
        // The state is deliberately not queried or mutated after the ordering guard.
    }
}
