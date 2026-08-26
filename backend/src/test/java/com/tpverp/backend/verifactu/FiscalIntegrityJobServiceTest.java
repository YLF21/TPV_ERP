package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tpverp.backend.installation.Installation;
import com.tpverp.backend.installation.InstallationRepository;
import com.tpverp.backend.licensing.LicenseRepository;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class FiscalIntegrityJobServiceTest {

    @Test
    void workerUsesOneExecutionTokenForHeartbeatCompletionAndFailureOwnership() {
        var fixture = fixture();
        var job = new FiscalIntegrityJob(fixture.companyId, fixture.storeId,
                fixture.installationId, "user", FiscalMode.NO_VERIFACTU, 8, 3, Instant.now());
        when(fixture.jobs.claimQueued(eq(job.getId()), any(UUID.class), any(Instant.class)))
                .thenReturn(1);
        when(fixture.jobs.findById(job.getId())).thenReturn(Optional.of(job));
        when(fixture.jobs.updateProgress(eq(job.getId()), any(UUID.class), anyLong(), anyLong(),
                anyLong(), anyLong(), anyLong(), anyString(), any(Instant.class))).thenReturn(1);
        when(fixture.jobs.markCompleted(eq(job.getId()), any(UUID.class), anyLong(), anyLong(),
                anyLong(), anyLong(), anyLong(), anyString(), any(Instant.class))).thenReturn(1);
        when(fixture.integrity.checkSnapshot(eq(fixture.companyId), eq(fixture.installationId),
                eq(FiscalMode.NO_VERIFACTU), eq(8L), eq(3L), any()))
                .thenAnswer(invocation -> {
                    var listener = invocation.getArgument(5, IntegrityProgressListener.class);
                    listener.onProgress(8, 3, 1, 1, 0, List.of("INTEGRIDAD_SNAPSHOT_8"));
                    return new FiscalIntegrityCheckView(Instant.now(), FiscalMode.NO_VERIFACTU,
                            false, List.of("INTEGRIDAD_SNAPSHOT_8"), 8, 3, 1, 1, 0);
                });

        fixture.service.run(job.getId());

        var claimed = ArgumentCaptor.forClass(UUID.class);
        verify(fixture.jobs).claimQueued(eq(job.getId()), claimed.capture(), any(Instant.class));
        verify(fixture.jobs).updateProgress(eq(job.getId()), eq(claimed.getValue()),
                eq(8L), eq(3L), eq(1L), eq(1L), eq(0L), anyString(), any(Instant.class));
        verify(fixture.jobs).markCompleted(eq(job.getId()), eq(claimed.getValue()),
                eq(8L), eq(3L), eq(1L), eq(1L), eq(0L), anyString(), any(Instant.class));
        verify(fixture.jobs, never()).markFailedIfRunning(any(), any(), anyString(), any());
    }

    @Test
    void lostExecutionTokenStopsWorkerAndCannotFailNewOwner() {
        var fixture = fixture();
        var job = new FiscalIntegrityJob(fixture.companyId, fixture.storeId,
                fixture.installationId, "user", FiscalMode.NO_VERIFACTU, 1, 0, Instant.now());
        when(fixture.jobs.claimQueued(eq(job.getId()), any(UUID.class), any(Instant.class)))
                .thenReturn(1);
        when(fixture.jobs.findById(job.getId())).thenReturn(Optional.of(job));
        when(fixture.jobs.updateProgress(eq(job.getId()), any(UUID.class), anyLong(), anyLong(),
                anyLong(), anyLong(), anyLong(), anyString(), any(Instant.class))).thenReturn(0);
        when(fixture.integrity.checkSnapshot(any(), any(), any(), anyLong(), anyLong(), any()))
                .thenAnswer(invocation -> {
                    invocation.getArgument(5, IntegrityProgressListener.class)
                            .onProgress(0, 0, 0, 0, 0, List.of());
                    throw new AssertionError("listener must abort the scan");
                });

        fixture.service.run(job.getId());

        var token = ArgumentCaptor.forClass(UUID.class);
        verify(fixture.jobs).claimQueued(eq(job.getId()), token.capture(), any(Instant.class));
        verify(fixture.jobs).markFailedIfRunning(eq(job.getId()), eq(token.getValue()),
                eq("fiscal_integrity_failed"), any(Instant.class));
        verify(fixture.jobs, never()).markCompleted(any(), any(), anyLong(), anyLong(),
                anyLong(), anyLong(), anyLong(), anyString(), any());
    }

    @Test
    void staleRecoveryReturnsOnlyJobsWhoseTokenWasActuallyRevoked() {
        var fixture = fixture();
        var recovered = UUID.randomUUID();
        var refreshed = UUID.randomUUID();
        when(fixture.jobs.findStaleRunningIds(any(Instant.class)))
                .thenReturn(List.of(recovered, refreshed));
        when(fixture.jobs.requeueRunningJob(eq(recovered), any(Instant.class), any(Instant.class)))
                .thenReturn(1);
        when(fixture.jobs.requeueRunningJob(eq(refreshed), any(Instant.class), any(Instant.class)))
                .thenReturn(0);

        assertThat(fixture.service.recoverStaleJobs()).containsExactly(recovered);
    }

    @Test
    void creationLocksConfigurationAndRejectsConcurrentActiveScope() {
        var fixture = fixture();
        var company = mock(Company.class);
        var store = mock(Store.class);
        var installation = mock(Installation.class);
        var configuration = mock(VerifactuConfiguration.class);
        when(company.getId()).thenReturn(fixture.companyId);
        when(store.getId()).thenReturn(fixture.storeId);
        when(store.getEmpresa()).thenReturn(company);
        when(installation.getId()).thenReturn(fixture.installationId);
        when(configuration.getCurrentMode()).thenReturn(FiscalMode.VERIFACTU);
        when(fixture.organization.currentCompany()).thenReturn(company);
        when(fixture.organization.currentStore()).thenReturn(store);
        when(fixture.licenses.findActiveByTiendaId(fixture.storeId)).thenReturn(List.of());
        when(fixture.installations.findAll()).thenReturn(List.of(installation));
        when(fixture.configurations.findForUpdateByCompanyId(fixture.companyId))
                .thenReturn(Optional.of(configuration));
        when(fixture.jobs.countByCompanyIdAndInstallationIdAndStatusIn(
                eq(fixture.companyId), eq(fixture.installationId), any())).thenReturn(1L);

        assertThatThrownBy(() -> fixture.service.create("admin"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("fiscal_integrity_active_limit");

        verify(fixture.configurations).insertIfMissingWithMode(any(), eq(fixture.companyId),
                eq("PRE_SIF"));
        verify(fixture.configurations).findForUpdateByCompanyId(fixture.companyId);
        verify(fixture.jobs, never()).save(any());
        verify(fixture.jdbc).query(anyString(), any(MapSqlParameterSource.class),
                any(RowCallbackHandler.class));
    }

    private static Fixture fixture() {
        var organization = mock(CurrentOrganization.class);
        var installations = mock(InstallationRepository.class);
        var licenses = mock(LicenseRepository.class);
        var configurations = mock(VerifactuConfigurationRepository.class);
        var records = mock(FiscalRecordRepository.class);
        var events = mock(FiscalEventRepository.class);
        var jobs = mock(FiscalIntegrityJobRepository.class);
        var integrity = mock(FiscalIntegrityService.class);
        var jdbc = mock(NamedParameterJdbcTemplate.class);
        var runtime = mock(FiscalRuntimeProperties.class);
        var service = new FiscalIntegrityJobService(organization, installations, licenses,
                configurations, records, events, jobs, integrity, jdbc, runtime);
        return new Fixture(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), organization,
                installations, licenses, configurations, jobs, integrity, jdbc, service);
    }

    private record Fixture(UUID companyId, UUID storeId, UUID installationId,
            CurrentOrganization organization, InstallationRepository installations,
            LicenseRepository licenses, VerifactuConfigurationRepository configurations,
            FiscalIntegrityJobRepository jobs, FiscalIntegrityService integrity,
            NamedParameterJdbcTemplate jdbc, FiscalIntegrityJobService service) {}
}
