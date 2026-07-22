package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tpverp.backend.audit.AuditResult;
import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.organization.StoreRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class VerifactuSecretDeletionServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-22T10:00:00Z");

    @Test
    void claimsDueJobsAndCompletesWithoutAuditingTheSecretPath() {
        var fixture = fixture();
        var job = job();
        var owner = UUID.randomUUID();
        when(fixture.jobs.findClaimable(NOW, 20)).thenReturn(List.of(job));
        when(fixture.jobs.findById(job.getId())).thenReturn(Optional.of(job));

        var claimed = fixture.service.claim(owner, 20);
        assertThat(claimed).singleElement().satisfies(value -> {
            assertThat(value.id()).isEqualTo(job.getId());
            assertThat(value.secretPath()).isEqualTo(job.getSecretPath());
        });
        assertThat(fixture.service.complete(job.getId(), owner)).isTrue();

        @SuppressWarnings("unchecked")
        var details = ArgumentCaptor.forClass(Map.class);
        verify(fixture.audit).recordSystem(
                eq(null), eq("VERIFACTU_CERTIFICATE_SECRET_DELETED"),
                eq(AuditResult.EXITO), details.capture());
        assertThat(details.getValue())
                .containsEntry("certificateId", job.getCertificateId())
                .doesNotContainKeys("secretPath", "path", "error");
    }

    @Test
    void failureUsesBoundedBackoffAndStableNonSensitiveError() {
        var fixture = fixture();
        var job = job();
        var owner = UUID.randomUUID();
        when(fixture.jobs.findClaimable(NOW, 1)).thenReturn(List.of(job));
        when(fixture.jobs.findById(job.getId())).thenReturn(Optional.of(job));
        fixture.service.claim(owner, 1);

        assertThat(fixture.service.retry(job.getId(), owner)).isTrue();

        assertThat(job.getStatus()).isEqualTo(VerifactuSecretDeletionStatus.PENDIENTE);
        assertThat(job.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(60));
        assertThat(job.getLastError()).isEqualTo("SECRET_DELETE_FAILED");
        verify(fixture.audit).recordSystem(
                eq(null), eq("VERIFACTU_CERTIFICATE_SECRET_DELETE_FAILED"),
                eq(AuditResult.FALLO), any());
    }

    @Test
    void enqueueIsIdempotentByProtectedPath() {
        var fixture = fixture();
        var existing = job();
        when(fixture.jobs.findBySecretPath(existing.getSecretPath()))
                .thenReturn(Optional.of(existing));

        var result = fixture.service.enqueue(
                existing.getCompanyId(), existing.getCertificateId(),
                existing.getSecretPath(), VerifactuSecretDeletionReason.ACTIVE_DELETED);

        assertThat(result).isSameAs(existing);
    }

    private static Fixture fixture() {
        var jobs = mock(VerifactuSecretDeletionJobRepository.class);
        var stores = mock(StoreRepository.class);
        var audit = mock(AuditService.class);
        when(stores.findByEmpresaId(any())).thenReturn(List.of());
        return new Fixture(jobs, audit, new VerifactuSecretDeletionService(
                jobs, stores, audit, Clock.fixed(NOW, ZoneOffset.UTC)));
    }

    private static VerifactuSecretDeletionJob job() {
        return VerifactuSecretDeletionJob.pending(
                UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID() + "/" + UUID.randomUUID() + "/private-key.dpapi",
                VerifactuSecretDeletionReason.RETENTION_PURGE, NOW);
    }

    private record Fixture(
            VerifactuSecretDeletionJobRepository jobs,
            AuditService audit,
            VerifactuSecretDeletionService service) {
    }
}
