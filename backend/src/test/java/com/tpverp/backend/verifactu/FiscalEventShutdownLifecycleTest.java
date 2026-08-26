package com.tpverp.backend.verifactu;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tpverp.backend.installation.Installation;
import com.tpverp.backend.installation.InstallationRepository;
import com.tpverp.backend.licensing.LicenseRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class FiscalEventShutdownLifecycleTest {
    private static final Instant SHUTDOWN_AT = Instant.parse("2026-08-26T18:00:00Z");

    @Test
    void soloProcesaEmpresasNoVerifactuYResuelveLaInstalacionActiva() {
        var noVerifactuCompany = UUID.randomUUID();
        var verifactuCompany = UUID.randomUUID();
        var installationId = UUID.randomUUID();
        var installation = mock(Installation.class);
        when(installation.getId()).thenReturn(installationId);
        var configurations = mock(VerifactuConfigurationRepository.class);
        var noVerifactu = mock(VerifactuConfiguration.class);
        when(noVerifactu.getCompanyId()).thenReturn(noVerifactuCompany);
        when(configurations.findAllByCurrentMode(FiscalMode.NO_VERIFACTU))
                .thenReturn(List.of(noVerifactu));
        var installations = mock(InstallationRepository.class);
        when(installations.findAll()).thenReturn(List.of(installation));
        var licenses = mock(LicenseRepository.class);
        when(licenses.findActiveByCompanyId(noVerifactuCompany)).thenReturn(List.of());
        var events = mock(FiscalEventService.class);
        var lifecycle = new FiscalEventShutdownLifecycle(configurations, installations, licenses,
                events, Clock.fixed(SHUTDOWN_AT, ZoneOffset.UTC));

        lifecycle.stop();

        verify(events).createSummaryBeforeShutdown(noVerifactuCompany, installationId,
                FiscalMode.NO_VERIFACTU, SHUTDOWN_AT);
        verify(configurations).findAllByCurrentMode(FiscalMode.NO_VERIFACTU);
        verify(configurations, never()).findByCompanyId(verifactuCompany);
    }

    @Test
    void elCierreEsIdempotenteYSiempreLiberaElCallback() {
        var companyId = UUID.randomUUID();
        var installation = mock(Installation.class);
        var installationId = UUID.randomUUID();
        when(installation.getId()).thenReturn(installationId);
        var configuration = mock(VerifactuConfiguration.class);
        when(configuration.getCompanyId()).thenReturn(companyId);
        var configurations = mock(VerifactuConfigurationRepository.class);
        when(configurations.findAllByCurrentMode(FiscalMode.NO_VERIFACTU))
                .thenReturn(List.of(configuration));
        var installations = mock(InstallationRepository.class);
        when(installations.findAll()).thenReturn(List.of(installation));
        var licenses = mock(LicenseRepository.class);
        when(licenses.findActiveByCompanyId(companyId)).thenReturn(List.of());
        var events = mock(FiscalEventService.class);
        var lifecycle = new FiscalEventShutdownLifecycle(configurations, installations, licenses,
                events, Clock.fixed(SHUTDOWN_AT, ZoneOffset.UTC));
        var callbacks = new AtomicInteger();

        lifecycle.stop(callbacks::incrementAndGet);
        lifecycle.stop(callbacks::incrementAndGet);

        verify(events).createSummaryBeforeShutdown(companyId, installationId,
                FiscalMode.NO_VERIFACTU, SHUTDOWN_AT);
        org.assertj.core.api.Assertions.assertThat(callbacks).hasValue(2);
        org.assertj.core.api.Assertions.assertThat(lifecycle.isRunning()).isFalse();
    }

    @Test
    void unFalloDeUnaEmpresaNoImpideIntentarLasDemasNiRompeElCierre() {
        var firstCompany = UUID.randomUUID();
        var secondCompany = UUID.randomUUID();
        var firstConfiguration = mock(VerifactuConfiguration.class);
        var secondConfiguration = mock(VerifactuConfiguration.class);
        when(firstConfiguration.getCompanyId()).thenReturn(firstCompany);
        when(secondConfiguration.getCompanyId()).thenReturn(secondCompany);
        var configurations = mock(VerifactuConfigurationRepository.class);
        when(configurations.findAllByCurrentMode(FiscalMode.NO_VERIFACTU))
                .thenReturn(List.of(firstConfiguration, secondConfiguration));
        var installations = mock(InstallationRepository.class);
        var firstInstallation = mock(Installation.class);
        var secondInstallation = mock(Installation.class);
        when(firstInstallation.getId()).thenReturn(UUID.randomUUID());
        when(secondInstallation.getId()).thenReturn(UUID.randomUUID());
        when(installations.findAll()).thenReturn(List.of(firstInstallation));
        var licenses = mock(LicenseRepository.class);
        when(licenses.findActiveByCompanyId(firstCompany)).thenReturn(List.of());
        when(licenses.findActiveByCompanyId(secondCompany)).thenReturn(List.of());
        var events = mock(FiscalEventService.class);
        when(events.createSummaryBeforeShutdown(firstCompany, firstInstallation.getId(),
                FiscalMode.NO_VERIFACTU, SHUTDOWN_AT)).thenThrow(new IllegalStateException("db"));
        var lifecycle = new FiscalEventShutdownLifecycle(configurations, installations, licenses,
                events, Clock.fixed(SHUTDOWN_AT, ZoneOffset.UTC));

        lifecycle.stop();

        verify(events).createSummaryBeforeShutdown(firstCompany, firstInstallation.getId(),
                FiscalMode.NO_VERIFACTU, SHUTDOWN_AT);
        verify(events).createSummaryBeforeShutdown(secondCompany, firstInstallation.getId(),
                FiscalMode.NO_VERIFACTU, SHUTDOWN_AT);
    }

    @Test
    void seDeclaraLifecycleAutomaticoYAntesDeLaDestruccionDePersistencia() {
        var lifecycle = new FiscalEventShutdownLifecycle(
                mock(VerifactuConfigurationRepository.class), mock(InstallationRepository.class),
                mock(LicenseRepository.class), mock(FiscalEventService.class),
                Clock.fixed(SHUTDOWN_AT, ZoneOffset.UTC));

        org.assertj.core.api.Assertions.assertThat(lifecycle.isAutoStartup()).isTrue();
        org.assertj.core.api.Assertions.assertThat(lifecycle.getPhase())
                .isEqualTo(Integer.MIN_VALUE);
    }
}
