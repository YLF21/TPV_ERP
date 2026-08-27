package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tpverp.backend.installation.InstallationRepository;
import com.tpverp.backend.installation.Installation;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TimeZone;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class FiscalExportServiceTest {

    @Test
    void prepareEventosRechazaMasDeMilReferenciasSinConsultarLaListaIlimitada() {
        var organization = mock(CurrentOrganization.class);
        var installations = mock(InstallationRepository.class);
        var configurations = mock(VerifactuConfigurationRepository.class);
        var events = mock(FiscalEventRepository.class);
        var company = new Company("B12345674", "Empresa DEV", Map.of(
                "linea1", "Calle 1", "ciudad", "Madrid", "codigoPostal", "28001",
                "provincia", "Madrid", "pais", "ES"));
        var store = new Store(company, "001", "Tienda", Map.of(
                "linea1", "Calle 1", "ciudad", "Madrid", "codigoPostal", "28001",
                "provincia", "Madrid", "pais", "ES"),
                "address-hash", "Europe/Madrid", "EUR", "es-ES");
        var installation = new Installation("INST-DEV-1", "public-key",
                Instant.parse("2026-08-24T08:00:00Z"));
        var references = java.util.stream.IntStream.range(0, 1001)
                .mapToObj(index -> mock(FiscalEventRepository.FiscalEventExportReference.class))
                .toList();
        when(organization.currentCompany()).thenReturn(company);
        when(organization.currentStore()).thenReturn(store);
        when(installations.findAll()).thenReturn(List.of(installation));
        when(configurations.findByCompanyId(company.getId())).thenReturn(Optional.of(
                new VerifactuConfiguration(company.getId(), FiscalMode.PRE_SIF)));
        when(events.findExportReferencesByPeriod(eq(company.getId()), eq(installation.getId()),
                isNull(), isNull(), org.mockito.ArgumentMatchers.any(
                        org.springframework.data.domain.Pageable.class))).thenReturn(references);

        var service = new FiscalExportService(organization, installations, configurations,
                mock(FiscalRecordRepository.class), mock(FiscalRecordArtifactRepository.class),
                events, mock(FiscalEventService.class), mock(FiscalExportRepository.class));

        assertThatThrownBy(() -> service.prepareExportZip(new FiscalExportRequest(FiscalExportKind.EVENTS)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("fiscal_export_use_export_jobs");
        verify(events, never()).findAllByCompanyIdAndInstallationIdOrderBySequenceAsc(
                company.getId(), installation.getId());
    }

    @Test
    void exportEventosRechazaMasDeMilEntidadesAntesDeLeerXml() {
        var organization = mock(CurrentOrganization.class);
        var installations = mock(InstallationRepository.class);
        var configurations = mock(VerifactuConfigurationRepository.class);
        var events = mock(FiscalEventRepository.class);
        var company = new Company("B12345674", "Empresa DEV", Map.of(
                "linea1", "Calle 1", "ciudad", "Madrid", "codigoPostal", "28001",
                "provincia", "Madrid", "pais", "ES"));
        var installation = new Installation("INST-DEV-1", "public-key",
                Instant.parse("2026-08-24T08:00:00Z"));
        var fiscalEvents = java.util.stream.IntStream.range(0, 1001)
                .mapToObj(index -> mock(FiscalEvent.class)).toList();
        when(organization.currentCompany()).thenReturn(company);
        when(installations.findAll()).thenReturn(List.of(installation));
        when(configurations.findByCompanyId(company.getId())).thenReturn(Optional.of(
                new VerifactuConfiguration(company.getId(), FiscalMode.PRE_SIF)));
        when(events.findExportBatchByPeriod(eq(company.getId()), eq(installation.getId()),
                isNull(), isNull(), org.mockito.ArgumentMatchers.any(
                        org.springframework.data.domain.Pageable.class))).thenReturn(fiscalEvents);

        var service = new FiscalExportService(organization, installations, configurations,
                mock(FiscalRecordRepository.class), mock(FiscalRecordArtifactRepository.class),
                events, mock(FiscalEventService.class), mock(FiscalExportRepository.class));

        assertThatThrownBy(() -> service.export(FiscalExportKind.EVENTS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("fiscal_export_use_export_jobs");
        verify(events, never()).findAllByCompanyIdAndInstallationIdOrderBySequenceAsc(
                company.getId(), installation.getId());
    }

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
        when(records.findExportBatchByFilters(eq(company.getId()), isNull(), eq(installation.getId()),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(),
                anyBoolean(), any(FiscalMode.class), any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(List.of(verifactu, noVerifactu));
        when(verifactuArtifact.getRecordId()).thenReturn(verifactu.getId());
        when(noVerifactuArtifact.getRecordId()).thenReturn(noVerifactu.getId());
        when(artifacts.findAllByRecordIdIn(any())).thenReturn(
                List.of(verifactuArtifact, noVerifactuArtifact));
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
        verify(artifacts).findAllByRecordIdIn(any());
        verify(artifacts, never()).findByRecordId(any());
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
        when(records.findExportBatchByFilters(eq(company.getId()), isNull(), eq(installation.getId()),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(),
                anyBoolean(), any(FiscalMode.class), any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(List.of(fiscalRecord));
        when(artifact.getRecordId()).thenReturn(fiscalRecord.getId());
        when(artifacts.findAllByRecordIdIn(any())).thenReturn(List.of(artifact));
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
        verify(artifacts, never()).findByRecordId(any());
    }

    @Test
    void exportacionEventosConservaHoraFirmadaAunqueCambieTimezoneJvm() {
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
        var generatedAt = Instant.parse("2026-08-24T10:00:00Z");
        var frozenTimestamp = OffsetDateTime.parse("2026-08-24T11:00:00+01:00");
        var persistedEvent = new FiscalEvent(
                company.getId(), installation.getId(), 1, FiscalEventType.OTHER,
                FiscalMode.NO_VERIFACTU, generatedAt, null, "C".repeat(64),
                eventXml(frozenTimestamp), "<signed/>", "D".repeat(64), generatedAt);
        var exportEvent = mock(FiscalEvent.class);

        when(organization.currentCompany()).thenReturn(company);
        when(installations.findAll()).thenReturn(List.of(installation));
        when(configurations.findByCompanyId(company.getId())).thenReturn(Optional.of(
                new VerifactuConfiguration(company.getId(), FiscalMode.NO_VERIFACTU)));
        when(events.findExportBatchByPeriod(eq(company.getId()), eq(installation.getId()),
                isNull(), isNull(), org.mockito.ArgumentMatchers.any(
                        org.springframework.data.domain.Pageable.class)))
                .thenReturn(List.of(persistedEvent));
        when(exportEvent.getId()).thenReturn(UUID.randomUUID());
        when(eventService.create(any(UUID.class), any(UUID.class), any(FiscalMode.class),
                any(FiscalEventType.class), isNull(),
                any(FiscalEventSummary.class), any(FiscalExportContext.class)))
                .thenReturn(exportEvent);
        when(exports.save(any(FiscalExport.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        var service = new FiscalExportService(organization, installations, configurations,
                records, artifacts, events, eventService, exports);

        var previousTimezone = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"));
            service.export(FiscalExportKind.EVENTS);
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"));
            service.export(FiscalExportKind.EVENTS);
        } finally {
            TimeZone.setDefault(previousTimezone);
        }

        var contexts = ArgumentCaptor.forClass(FiscalExportContext.class);
        verify(eventService, times(2)).create(
                any(UUID.class), any(UUID.class), any(FiscalMode.class),
                any(FiscalEventType.class), isNull(),
                any(FiscalEventSummary.class), contexts.capture());
        assertThat(contexts.getAllValues()).hasSize(2).allSatisfy(context -> {
            assertThat(context.periodStart()).isEqualTo(frozenTimestamp);
            assertThat(context.periodEnd()).isEqualTo(frozenTimestamp);
            assertThat(context.firstEvent().generatedAt()).isEqualTo(frozenTimestamp);
            assertThat(context.lastEvent().generatedAt()).isEqualTo(frozenTimestamp);
        });
        assertThat(contexts.getAllValues().get(0)).isEqualTo(contexts.getAllValues().get(1));
    }

    @Test
    void descargaZipEscribeXmlIncrementalmenteYNoCierraElStreamHttp() throws Exception {
        var companyId = UUID.randomUUID();
        var installationId = UUID.randomUUID();
        var storeId = UUID.randomUUID();
        var organization = mock(CurrentOrganization.class);
        var installations = mock(InstallationRepository.class);
        var configurations = mock(VerifactuConfigurationRepository.class);
        var records = mock(FiscalRecordRepository.class);
        var artifacts = mock(FiscalRecordArtifactRepository.class);
        var events = mock(FiscalEventRepository.class);
        var eventService = mock(FiscalEventService.class);
        var exports = mock(FiscalExportRepository.class);
        var fiscalRecord = record(companyId, installationId, FiscalMode.VERIFACTU, 1);
        var artifact = mock(FiscalRecordArtifact.class);
        when(artifacts.findFrozenXmlByRecordId(fiscalRecord.getId()))
                .thenReturn(Optional.of("<signed-record/>\n"));
        when(exports.save(any(FiscalExport.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var service = new FiscalExportService(organization, installations, configurations,
                records, artifacts, events, eventService, exports);
        var plan = new FiscalExportService.FiscalExportZipPlan(
                UUID.randomUUID(), companyId, storeId, installationId, FiscalExportKind.BILLING,
                FiscalMode.VERIFACTU, null, null, List.of(fiscalRecord), List.of(), Instant.now());
        var output = new NonClosingOutputStream();

        service.writeExportZip(plan, output);

        assertThat(output.closed).isFalse();
        var entries = new HashMap<String, String>();
        try (var zip = new java.util.zip.ZipInputStream(
                new java.io.ByteArrayInputStream(output.toByteArray()), java.nio.charset.StandardCharsets.UTF_8)) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entries.put(entry.getName(), new String(zip.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            }
        }
        assertThat(entries).containsEntry("registro-facturacion-000001.xml", "<signed-record/>\n")
                .containsKey("manifest.json");
        verify(eventService, never()).create(any(UUID.class), any(UUID.class), any(FiscalMode.class),
                any(FiscalEventType.class), isNull(), any(FiscalEventSummary.class), any(FiscalExportContext.class));
        verify(exports).save(any(FiscalExport.class));
    }

    private static final class NonClosingOutputStream extends ByteArrayOutputStream {
        private boolean closed;

        @Override
        public void close() throws IOException {
            closed = true;
        }
    }

    private static String eventXml(OffsetDateTime generatedAt) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <sum:RegistroEvento xmlns:sum="https://www2.agenciatributaria.gob.es/eventos">
                  <sum:Evento>
                    <sum:FechaHoraHusoGenEvento>%s</sum:FechaHoraHusoGenEvento>
                  </sum:Evento>
                </sum:RegistroEvento>
                """.formatted(generatedAt);
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
