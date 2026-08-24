package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tpverp.backend.installation.InstallationRepository;
import com.tpverp.backend.installation.Installation;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.CurrentOrganization;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FiscalExportServiceTest {

    @Test
    void rechazaTipoDeExportacionNuloAntesDeAccederAlContextoFiscal() {
        var service = new FiscalExportService(
                mock(CurrentOrganization.class),
                mock(InstallationRepository.class),
                mock(VerifactuConfigurationRepository.class),
                mock(FiscalRecordRepository.class),
                mock(FiscalRecordArtifactRepository.class),
                mock(FiscalEventRepository.class),
                mock(FiscalEventService.class),
                mock(FiscalExportRepository.class));

        assertThatThrownBy(() -> service.export(null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("El tipo de exportacion es obligatorio");
    }

    @Test
    void requerimientoNoVerifactuNoIncluyeRegistrosVerifactuHistoricos() {
        var organization = mock(CurrentOrganization.class);
        var installations = mock(InstallationRepository.class);
        var configurations = mock(VerifactuConfigurationRepository.class);
        var records = mock(FiscalRecordRepository.class);
        var artifacts = mock(FiscalRecordArtifactRepository.class);
        var events = mock(FiscalEventRepository.class);
        var eventService = mock(FiscalEventService.class);
        var exports = mock(FiscalExportRepository.class);
        var company = new Company("B12345674", "Empresa DEV", Map.of(
                "linea1", "Calle 1", "ciudad", "Madrid", "codigoPostal", "28001",
                "provincia", "Madrid", "pais", "ES"));
        var installation = new Installation("INST-DEV-1", "public-key",
                Instant.parse("2026-08-24T08:00:00Z"));
        var verifactu = record(company.getId(), installation.getId(), FiscalMode.VERIFACTU, 1);
        var noVerifactu = record(company.getId(), installation.getId(),
                FiscalMode.NO_VERIFACTU, 2);
        var verifactuArtifact = mock(FiscalRecordArtifact.class);
        var noVerifactuArtifact = mock(FiscalRecordArtifact.class);
        var exportEvent = mock(FiscalEvent.class);

        when(organization.currentCompany()).thenReturn(company);
        when(installations.findAll()).thenReturn(List.of(installation));
        when(configurations.findByCompanyId(company.getId())).thenReturn(Optional.of(
                new VerifactuConfiguration(company.getId(), FiscalMode.NO_VERIFACTU)));
        when(records.findAllByCompanyIdAndInstallationIdOrderBySequence(company.getId(),
                installation.getId())).thenReturn(List.of(verifactu, noVerifactu));
        when(artifacts.findByRecordId(verifactu.getId())).thenReturn(Optional.of(verifactuArtifact));
        when(artifacts.findByRecordId(noVerifactu.getId())).thenReturn(Optional.of(noVerifactuArtifact));
        when(verifactuArtifact.getUnsignedXml()).thenReturn("<verifactu/>");
        when(noVerifactuArtifact.getSignedXml()).thenReturn("<no-verifactu/>");
        when(exportEvent.getId()).thenReturn(UUID.randomUUID());
        when(eventService.create(any(UUID.class), any(UUID.class), any(FiscalMode.class),
                any(FiscalEventType.class), org.mockito.ArgumentMatchers.isNull(),
                any(FiscalEventSummary.class), any(FiscalExportContext.class)))
                .thenReturn(exportEvent);
        when(exports.save(any(FiscalExport.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var service = new FiscalExportService(organization, installations, configurations,
                records, artifacts, events, eventService, exports);

        var result = service.export(FiscalExportKind.BILLING);

        assertThat(result.recordCount()).isEqualTo(1);
        assertThat(result.xml()).containsExactly("<no-verifactu/>");
    }

    @Test
    void requerimientoConstruyeLoteOficialConLosXmlFirmadosCongelados() {
        var organization = mock(CurrentOrganization.class);
        var installations = mock(InstallationRepository.class);
        var configurations = mock(VerifactuConfigurationRepository.class);
        var records = mock(FiscalRecordRepository.class);
        var artifacts = mock(FiscalRecordArtifactRepository.class);
        var events = mock(FiscalEventRepository.class);
        var eventService = mock(FiscalEventService.class);
        var exports = mock(FiscalExportRepository.class);
        var company = new Company("B12345674", "Empresa DEV", Map.of(
                "linea1", "Calle 1", "ciudad", "Madrid", "codigoPostal", "28001",
                "provincia", "Madrid", "pais", "ES"));
        var installation = new Installation("INST-DEV-1", "public-key",
                Instant.parse("2026-08-24T08:00:00Z"));
        var fiscalRecord = record(company.getId(), installation.getId(),
                FiscalMode.NO_VERIFACTU, 1);
        var artifact = mock(FiscalRecordArtifact.class);
        var exportEvent = mock(FiscalEvent.class);
        var signedXml = new VerifactuXmlService().recordXml(
                new VerifactuXmlBatchRequest("Empresa DEV", "B12345674",
                        List.of(fiscalRecord),
                        new VerifactuSystemInfo("Fabricante TPV ERP", "B12345674",
                                "TPV ERP", "01", "0.0.1", "INST-DEV-1", true, false, false)),
                fiscalRecord);

        when(organization.currentCompany()).thenReturn(company);
        when(installations.findAll()).thenReturn(List.of(installation));
        when(configurations.findByCompanyId(company.getId())).thenReturn(Optional.of(
                new VerifactuConfiguration(company.getId(), FiscalMode.NO_VERIFACTU)));
        when(records.findAllByCompanyIdAndInstallationIdOrderBySequence(company.getId(),
                installation.getId())).thenReturn(List.of(fiscalRecord));
        when(artifacts.findByRecordId(fiscalRecord.getId())).thenReturn(Optional.of(artifact));
        when(artifact.getSignedXml()).thenReturn(signedXml);
        when(artifact.getUnsignedXml()).thenReturn(signedXml);
        when(exportEvent.getId()).thenReturn(UUID.randomUUID());
        when(eventService.create(any(UUID.class), any(UUID.class), any(FiscalMode.class),
                any(FiscalEventType.class), org.mockito.ArgumentMatchers.isNull(),
                any(FiscalEventSummary.class), any(FiscalExportContext.class)))
                .thenReturn(exportEvent);
        when(exports.save(any(FiscalExport.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var service = new FiscalExportService(organization, installations, configurations,
                records, artifacts, events, eventService, exports);

        var result = service.export(FiscalExportKind.BILLING, null, null, "REQ-2026-001");

        assertThat(result.batchXml()).contains("<sf:RemisionRequerimiento>")
                .contains("<sf:RefRequerimiento>REQ-2026-001</sf:RefRequerimiento>")
                .contains("<sf:FinRequerimiento>S</sf:FinRequerimiento>");
        new VerifactuOfficialXsdValidator().validate(result.batchXml());
    }

    private static FiscalRecord record(UUID companyId, UUID installationId,
            FiscalMode mode, long sequence) {
        var line = Map.<String, Object>of(
                "regimenImpuesto", "IVA",
                "porcentajeImpuesto", new BigDecimal("21.00"),
                "base", new BigDecimal("10.00"),
                "impuesto", new BigDecimal("2.10"));
        var snapshot = Map.<String, Object>of(
                "baseTotal", new BigDecimal("10.00"),
                "impuestoTotal", new BigDecimal("2.10"),
                "total", new BigDecimal("12.10"),
                "lineas", List.of(line));
        return new FiscalRecord(UUID.randomUUID(), companyId, installationId, UUID.randomUUID(),
                UUID.randomUUID(), sequence, FiscalRecordOperation.ALTA, FiscalDocumentType.F2,
                "T-" + sequence, LocalDate.of(2026, 8, 24),
                Instant.parse("2026-08-24T10:00:00Z"), "Atlantic/Canary", "B12345674",
                new BigDecimal("2.10"), new BigDecimal("12.10"), null,
                "A".repeat(64), "B".repeat(64), snapshot,
                "1.0", "SHA-256", "4.2.7", mode);
    }
}
