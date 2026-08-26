package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tpverp.backend.installation.InstallationRepository;
import com.tpverp.backend.licensing.LicenseRepository;
import com.tpverp.backend.organization.CompanyRepository;
import com.tpverp.backend.organization.StoreRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FiscalEventServiceShutdownSummaryTest {
    @Test
    void noDuplicaSummaryCuandoElUltimoEventoYaEsResumen() {
        var companyId = UUID.randomUUID();
        var installationId = UUID.randomUUID();
        var now = Instant.parse("2026-08-26T18:00:00Z");
        var events = mock(FiscalEventRepository.class);
        var lastEvent = mock(FiscalEvent.class);
        when(lastEvent.getType()).thenReturn(FiscalEventType.SUMMARY);
        when(events.findTopByCompanyIdAndInstallationIdOrderBySequenceDesc(
                companyId, installationId)).thenReturn(Optional.of(lastEvent));
        var chains = mock(FiscalEventChainRepository.class);
        when(chains.findForUpdate(companyId, installationId))
                .thenReturn(Optional.of(mock(FiscalEventChain.class)));
        var operatingClock = mock(FiscalOperatingClockService.class);
        var service = service(events, chains, operatingClock, now);

        assertThat(service.createSummaryBeforeShutdown(companyId, installationId,
                FiscalMode.NO_VERIFACTU, now)).isNull();

        verify(events, never()).save(any(FiscalEvent.class));
        verify(operatingClock, never()).reset(companyId, installationId, now);
    }

    @Test
    void ignoraElHookSiLaEmpresaYaNoEstaEnNoVerifactu() {
        var events = mock(FiscalEventRepository.class);
        var chains = mock(FiscalEventChainRepository.class);
        var service = service(events, chains, mock(FiscalOperatingClockService.class),
                Instant.parse("2026-08-26T18:00:00Z"));

        assertThat(service.createSummaryBeforeShutdown(UUID.randomUUID(), UUID.randomUUID(),
                FiscalMode.VERIFACTU, Instant.now())).isNull();

        verify(events, never()).findTopByCompanyIdAndInstallationIdOrderBySequenceDesc(
                any(), any());
        verify(chains, never()).findForUpdate(any(), any());
    }

    private static FiscalEventService service(FiscalEventRepository events,
            FiscalEventChainRepository chains, FiscalOperatingClockService operatingClock,
            Instant now) {
        return new FiscalEventService(
                mock(CompanyRepository.class), mock(StoreRepository.class),
                mock(LicenseRepository.class), mock(InstallationRepository.class),
                mock(FiscalSystemVersionRepository.class), mock(FiscalRecordRepository.class),
                chains, events, new FiscalEventXmlService(), mock(FiscalXadesSigner.class),
                operatingClock, mock(FiscalRuntimeProperties.class),
                Clock.fixed(now, ZoneOffset.UTC), "TPV ERP DEV", "B00000000", "TPV ERP",
                "TPVERP", "4.1.0");
    }
}
