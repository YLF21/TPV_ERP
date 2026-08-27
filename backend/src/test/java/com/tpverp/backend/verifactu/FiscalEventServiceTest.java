package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tpverp.backend.installation.Installation;
import com.tpverp.backend.installation.InstallationRepository;
import com.tpverp.backend.licensing.LicenseRepository;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.CompanyRepository;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.organization.StoreRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TimeZone;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FiscalEventServiceTest {

    @Test
    void cadaEventoUsaTimezonePersistidaEIdentidadSifDeUnSoloObligado() {
        var now = Instant.parse("2026-01-15T12:00:00.987654321Z");
        var company = new Company("B12345678", "Empresa DEV",
                Map.of("linea1", "Calle 1", "ciudad", "Madrid", "codigoPostal", "28001",
                        "provincia", "Madrid", "pais", "ES"));
        var installation = new Installation("INST-DEV-1", "public-key", Instant.now());
        var companies = mock(CompanyRepository.class);
        var stores = mock(StoreRepository.class);
        var licenses = mock(LicenseRepository.class);
        var installations = mock(InstallationRepository.class);
        var systemVersions = mock(FiscalSystemVersionRepository.class);
        var records = mock(FiscalRecordRepository.class);
        var chains = mock(FiscalEventChainRepository.class);
        var events = mock(FiscalEventRepository.class);
        var signer = mock(FiscalXadesSigner.class);
        var operatingClock = mock(FiscalOperatingClockService.class);
        var runtime = mock(FiscalRuntimeProperties.class);
        var store = new Store(company, "Tienda", Map.of(
                "linea1", "Calle 1", "ciudad", "Madrid", "codigoPostal", "28001",
                "provincia", "Madrid", "pais", "ES"),
                "address-hash", "Europe/Madrid", "EUR", "es-ES");
        var chain = new FiscalEventChain(company.getId(), installation.getId(), Instant.now());
        var systemVersion = new FiscalSystemVersion(company.getId(), installation.getId(),
                "B00000000", "TPV ERP DEV", "TPV ERP", "TPVERP", "4.1.0",
                installation.getReferencia(), "E".repeat(64), true, Instant.now());

        when(companies.findById(company.getId())).thenReturn(Optional.of(company));
        when(stores.findByEmpresaId(company.getId())).thenReturn(List.of(store));
        when(licenses.findActiveStoreTimezonesByCompanyIdAndInstallationId(
                company.getId(), installation.getId())).thenReturn(List.of());
        when(installations.findById(installation.getId())).thenReturn(Optional.of(installation));
        when(records.findTopByCompanyIdAndInstallationIdOrderBySequenceDesc(
                company.getId(), installation.getId())).thenReturn(Optional.empty());
        when(systemVersions.findByCompanyIdAndInstallationIdAndSystemVersionAndInstallationNumber(
                company.getId(), installation.getId(), "4.1.0", installation.getReferencia()))
                .thenReturn(Optional.of(systemVersion));
        when(runtime.isSandbox()).thenReturn(true);
        when(runtime.declarationHash()).thenReturn("E".repeat(64));
        when(chains.findForUpdate(company.getId(), installation.getId()))
                .thenReturn(Optional.of(chain));
        when(signer.signEvent(any(), any(), any())).thenReturn("<signed/>");
        when(events.save(any(FiscalEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var service = new FiscalEventService(companies, stores, licenses, installations,
                systemVersions, records, chains,
                events, new FiscalEventXmlService(), signer, operatingClock, runtime,
                Clock.fixed(now, ZoneOffset.UTC),
                "TPV ERP DEV", "B00000000", "TPV ERP", "TPVERP", "4.1.0");

        var previousTimezone = TimeZone.getDefault();
        FiscalEvent event;
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"));
            event = service.create(
                    company.getId(), installation.getId(), FiscalMode.NO_VERIFACTU,
                    FiscalEventType.START_NO_VERIFACTU, "inicio laboratorio");
        } finally {
            TimeZone.setDefault(previousTimezone);
        }

        assertThat(event.getSystemVersionId()).isEqualTo(systemVersion.getId());
        assertThat(systemVersion.getDeclarationHash()).isEqualTo("E".repeat(64));
        assertThat(event.getSequence()).isEqualTo(1);
        assertThat(event.getGeneratedAt()).isEqualTo(Instant.parse("2026-01-15T12:00:00Z"));
        var exactXmlTimestamp = "2026-01-15T13:00:00+01:00";
        var expectedHash = sha256(
                "NIF=B00000000&ID=&IdSistemaInformatico=TPVERP&Version=4.1.0"
                        + "&NumeroInstalacion=INST-DEV-1&NIF=B12345678&TipoEvento=01"
                        + "&HuellaEvento=&FechaHoraHusoGenEvento=" + exactXmlTimestamp);
        assertThat(event.getHash()).isEqualTo(expectedHash);
        assertThat(event.getUnsignedXml())
                .contains("<sum:FechaHoraHusoGenEvento>" + exactXmlTimestamp
                        + "</sum:FechaHoraHusoGenEvento>")
                .contains("<sum:HuellaEvento>" + expectedHash + "</sum:HuellaEvento>")
                .contains("<sum:TipoUsoPosibleMultiOT>N</sum:TipoUsoPosibleMultiOT>")
                .contains("<sum:IndicadorMultiplesOT>N</sum:IndicadorMultiplesOT>")
                .doesNotContain("2026-01-15T21:00:00+09:00");
    }

    @Test
    void antesDelPrimerRegistroUsaLaTiendaDeLaLicenciaActivaEnEmpresaMultiTienda() {
        var now = Instant.parse("2026-07-15T12:00:00Z");
        var company = company();
        var installation = new Installation("INST-DEV-2", "public-key", now);
        var companies = mock(CompanyRepository.class);
        var stores = mock(StoreRepository.class);
        var licenses = mock(LicenseRepository.class);
        var installations = mock(InstallationRepository.class);
        var systemVersions = mock(FiscalSystemVersionRepository.class);
        var records = mock(FiscalRecordRepository.class);
        var chains = mock(FiscalEventChainRepository.class);
        var events = mock(FiscalEventRepository.class);
        var signer = mock(FiscalXadesSigner.class);
        var operatingClock = mock(FiscalOperatingClockService.class);
        var runtime = mock(FiscalRuntimeProperties.class);
        var chain = new FiscalEventChain(company.getId(), installation.getId(), now);
        var systemVersion = systemVersion(company, installation, now);

        when(companies.findById(company.getId())).thenReturn(Optional.of(company));
        when(installations.findById(installation.getId())).thenReturn(Optional.of(installation));
        when(records.findTopByCompanyIdAndInstallationIdOrderBySequenceDesc(
                company.getId(), installation.getId())).thenReturn(Optional.empty());
        when(licenses.findActiveStoreTimezonesByCompanyIdAndInstallationId(
                company.getId(), installation.getId())).thenReturn(List.of("Atlantic/Canary"));
        when(systemVersions.findByCompanyIdAndInstallationIdAndSystemVersionAndInstallationNumber(
                company.getId(), installation.getId(), "4.1.0", installation.getReferencia()))
                .thenReturn(Optional.of(systemVersion));
        when(runtime.isSandbox()).thenReturn(true);
        when(runtime.declarationHash()).thenReturn("E".repeat(64));
        when(chains.findForUpdate(company.getId(), installation.getId()))
                .thenReturn(Optional.of(chain));
        when(signer.signEvent(any(), any(), any())).thenReturn("<signed/>");
        when(events.save(any(FiscalEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var service = new FiscalEventService(companies, stores, licenses, installations,
                systemVersions, records, chains,
                events, new FiscalEventXmlService(), signer, operatingClock, runtime,
                Clock.fixed(now, ZoneOffset.UTC),
                "TPV ERP DEV", "B00000000", "TPV ERP", "TPVERP", "4.1.0");

        var previousTimezone = TimeZone.getDefault();
        FiscalEvent event;
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"));
            event = service.create(
                    company.getId(), installation.getId(), FiscalMode.NO_VERIFACTU,
                    FiscalEventType.START_NO_VERIFACTU, "inicio tienda canaria");
        } finally {
            TimeZone.setDefault(previousTimezone);
        }

        assertThat(event.getUnsignedXml())
                .contains("<sum:FechaHoraHusoGenEvento>2026-07-15T13:00:00+01:00"
                        + "</sum:FechaHoraHusoGenEvento>")
                .doesNotContain("2026-07-15T14:00:00+02:00")
                .doesNotContain("2026-07-15T21:00:00+09:00");
        verifyNoInteractions(stores);
    }

    @Test
    void bloqueaLicenciasActivasConTimezonesDistintasParaLaMismaInstalacion() {
        var now = Instant.parse("2026-07-15T12:00:00Z");
        var company = company();
        var installation = new Installation("INST-DEV-3", "public-key", now);
        var companies = mock(CompanyRepository.class);
        var stores = mock(StoreRepository.class);
        var licenses = mock(LicenseRepository.class);
        var installations = mock(InstallationRepository.class);
        var records = mock(FiscalRecordRepository.class);

        when(companies.findById(company.getId())).thenReturn(Optional.of(company));
        when(installations.findById(installation.getId())).thenReturn(Optional.of(installation));
        when(records.findTopByCompanyIdAndInstallationIdOrderBySequenceDesc(
                company.getId(), installation.getId())).thenReturn(Optional.empty());
        when(licenses.findActiveStoreTimezonesByCompanyIdAndInstallationId(
                company.getId(), installation.getId()))
                .thenReturn(List.of("Atlantic/Canary", "Europe/Madrid"));

        var service = new FiscalEventService(companies, stores, licenses, installations,
                mock(FiscalSystemVersionRepository.class), records,
                mock(FiscalEventChainRepository.class), mock(FiscalEventRepository.class),
                new FiscalEventXmlService(), mock(FiscalXadesSigner.class),
                mock(FiscalOperatingClockService.class), mock(FiscalRuntimeProperties.class),
                Clock.fixed(now, ZoneOffset.UTC),
                "TPV ERP DEV", "B00000000", "TPV ERP", "TPVERP", "4.1.0");

        assertThatThrownBy(() -> service.create(
                company.getId(), installation.getId(), FiscalMode.NO_VERIFACTU,
                FiscalEventType.START_NO_VERIFACTU, "inicio ambiguo"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("licencias activas con timezones fiscales distintas");
        verifyNoInteractions(stores);
    }

    private static Company company() {
        return new Company("B12345678", "Empresa DEV",
                Map.of("linea1", "Calle 1", "ciudad", "Madrid", "codigoPostal", "28001",
                        "provincia", "Madrid", "pais", "ES"));
    }

    private static FiscalSystemVersion systemVersion(
            Company company, Installation installation, Instant createdAt) {
        return new FiscalSystemVersion(company.getId(), installation.getId(),
                "B00000000", "TPV ERP DEV", "TPV ERP", "TPVERP", "4.1.0",
                installation.getReferencia(), "E".repeat(64), true, createdAt);
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().withUpperCase().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(
                            value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }
}
