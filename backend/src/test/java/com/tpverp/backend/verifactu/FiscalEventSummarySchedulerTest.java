package com.tpverp.backend.verifactu;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class FiscalEventSummarySchedulerTest {
    @Test
    void noArrancaEnRealMientrasProduccionSigaBloqueada() {
        var configurations = mock(VerifactuConfigurationRepository.class);
        var installations = mock(com.tpverp.backend.installation.InstallationRepository.class);
        var events = mock(FiscalEventService.class);
        var runtime = new FiscalRuntimeProperties(new MockEnvironment()
                .withProperty("tpv.verifactu.runtime-class", "REAL")
                .withProperty("tpv.verifactu.production-enabled", "false"));
        var scheduler = new FiscalEventSummaryScheduler(configurations, installations, events,
                runtime, Clock.systemUTC());

        scheduler.emitDueSummaries();

        verify(configurations, never()).findAllByCurrentMode(FiscalMode.NO_VERIFACTU);
    }

    @Test
    void recorreConfiguracionesNoVerifactuEnSandbox() {
        var companyId = UUID.randomUUID();
        var installationId = UUID.randomUUID();
        var configuration = mock(VerifactuConfiguration.class);
        when(configuration.getCompanyId()).thenReturn(companyId);
        var installation = mock(com.tpverp.backend.installation.Installation.class);
        when(installation.getId()).thenReturn(installationId);
        var configurations = mock(VerifactuConfigurationRepository.class);
        when(configurations.findAllByCurrentMode(FiscalMode.NO_VERIFACTU))
                .thenReturn(List.of(configuration));
        var installations = mock(com.tpverp.backend.installation.InstallationRepository.class);
        when(installations.findAll()).thenReturn(List.of(installation));
        var events = mock(FiscalEventService.class);
        var runtime = new FiscalRuntimeProperties(new MockEnvironment()
                .withProperty("tpv.verifactu.runtime-class", "SANDBOX")
                .withProperty("tpv.verifactu.dev-sandbox.enabled", "true")
                .withProperty("tpv.verifactu.endpoint-environment", "TEST")
                .withProperty("tpv.verifactu.transport-mode", "SIMULATED"));
        var clock = Clock.fixed(Instant.parse("2026-08-23T18:00:00Z"), ZoneOffset.UTC);
        var scheduler = new FiscalEventSummaryScheduler(configurations, installations, events,
                runtime, clock);

        scheduler.emitDueSummaries();

        verify(events).createSummaryIfDue(companyId, installationId,
                FiscalMode.NO_VERIFACTU, Instant.parse("2026-08-23T18:00:00Z"));
    }
}
