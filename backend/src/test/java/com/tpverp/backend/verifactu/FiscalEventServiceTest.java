package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tpverp.backend.installation.Installation;
import com.tpverp.backend.installation.InstallationRepository;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.CompanyRepository;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FiscalEventServiceTest {

    @Test
    void cadaEventoNuevoEnlazaLaVersionSifCongelada() {
        var company = new Company("B12345678", "Empresa DEV",
                Map.of("linea1", "Calle 1", "ciudad", "Madrid", "codigoPostal", "28001",
                        "provincia", "Madrid", "pais", "ES"));
        var installation = new Installation("INST-DEV-1", "public-key", Instant.now());
        var companies = mock(CompanyRepository.class);
        var installations = mock(InstallationRepository.class);
        var systemVersions = mock(FiscalSystemVersionRepository.class);
        var chains = mock(FiscalEventChainRepository.class);
        var events = mock(FiscalEventRepository.class);
        var signer = mock(FiscalXadesSigner.class);
        var operatingClock = mock(FiscalOperatingClockService.class);
        var runtime = mock(FiscalRuntimeProperties.class);
        var chain = new FiscalEventChain(company.getId(), installation.getId(), Instant.now());
        var systemVersion = new FiscalSystemVersion(company.getId(), installation.getId(),
                "B00000000", "TPV ERP DEV", "TPV ERP", "TPVERP", "4.1.0",
                installation.getReferencia(), null, true, Instant.now());

        when(companies.findById(company.getId())).thenReturn(Optional.of(company));
        when(installations.findById(installation.getId())).thenReturn(Optional.of(installation));
        when(systemVersions.findByCompanyIdAndInstallationIdAndSystemVersionAndInstallationNumber(
                company.getId(), installation.getId(), "4.1.0", installation.getReferencia()))
                .thenReturn(Optional.of(systemVersion));
        when(runtime.isSandbox()).thenReturn(true);
        when(chains.findForUpdate(company.getId(), installation.getId()))
                .thenReturn(Optional.of(chain));
        when(signer.signEvent(any(), any(), any())).thenReturn("<signed/>");
        when(events.save(any(FiscalEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var service = new FiscalEventService(companies, installations, systemVersions, chains,
                events, new FiscalEventXmlService(), signer, operatingClock, runtime,
                "TPV ERP DEV", "B00000000", "TPV ERP", "TPVERP", "4.1.0");

        var event = service.create(company.getId(), installation.getId(), FiscalMode.NO_VERIFACTU,
                FiscalEventType.START_NO_VERIFACTU, "inicio laboratorio");

        assertThat(event.getSystemVersionId()).isEqualTo(systemVersion.getId());
        assertThat(event.getSequence()).isEqualTo(1);
    }
}
