package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tpverp.backend.installation.Installation;
import com.tpverp.backend.installation.InstallationRepository;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.CurrentOrganization;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class FiscalIntegrityServiceTest {

    @Test
    void detectaAlteracionDeSnapshotYXmlDeEventoComoAnomaliaBloqueante() {
        var company = new Company("B00000000", "Empresa DEV", Map.of(
                "linea1", "Calle 1", "ciudad", "Las Palmas", "codigoPostal", "35001",
                "provincia", "Las Palmas", "pais", "ES"));
        var installation = mock(Installation.class);
        when(installation.getId()).thenReturn(java.util.UUID.randomUUID());
        var organization = mock(CurrentOrganization.class);
        when(organization.currentCompany()).thenReturn(company);
        var installations = mock(InstallationRepository.class);
        when(installations.findAll()).thenReturn(List.of(installation));
        var configurations = mock(VerifactuConfigurationRepository.class);
        var configuration = new VerifactuConfiguration(company.getId());
        configuration.changeMode(FiscalMode.NO_VERIFACTU, Instant.now(), null);
        when(configurations.findByCompanyId(company.getId())).thenReturn(Optional.of(configuration));

        var record = mock(FiscalRecord.class);
        when(record.getSequence()).thenReturn(1L);
        when(record.getSnapshot()).thenReturn(Map.of("total", new BigDecimal("12.10")));
        when(record.getSnapshotHash()).thenReturn("0".repeat(64));
        var records = mock(FiscalRecordRepository.class);
        when(records.findAllByCompanyIdAndInstallationIdOrderBySequence(
                company.getId(), installation.getId())).thenReturn(List.of(record));

        var event = mock(FiscalEvent.class);
        when(event.getSequence()).thenReturn(2L);
        when(event.getSignedXml()).thenReturn("<RegistroEvento/>");
        when(event.getXmlHash()).thenReturn("F".repeat(64));
        var events = mock(FiscalEventRepository.class);
        when(events.findAllByCompanyIdAndInstallationIdOrderBySequenceAsc(
                company.getId(), installation.getId())).thenReturn(List.of(event));
        var alarms = mock(FiscalAlarmRepository.class);
        var eventService = mock(FiscalEventService.class);
        var service = new FiscalIntegrityService(organization, installations, configurations,
                records, events, alarms, eventService, new FiscalQrUrlService());

        var result = service.check();

        assertThat(result.ok()).isFalse();
        assertThat(result.anomalies()).containsExactlyInAnyOrder(
                "INTEGRIDAD_SNAPSHOT_1", "INTEGRIDAD_XML_EVENTO_2");
        verify(alarms, org.mockito.Mockito.times(2))
                .save(org.mockito.ArgumentMatchers.any(FiscalAlarm.class));
    }

    @Test
    void detectaArtefactoYSnapshotDeImpresionAusentes() {
        var company = new Company("B00000000", "Empresa DEV", Map.of(
                "linea1", "Calle 1", "ciudad", "Las Palmas", "codigoPostal", "35001",
                "provincia", "Las Palmas", "pais", "ES"));
        var installation = mock(Installation.class);
        var installationId = java.util.UUID.randomUUID();
        when(installation.getId()).thenReturn(installationId);
        var organization = mock(CurrentOrganization.class);
        when(organization.currentCompany()).thenReturn(company);
        var installations = mock(InstallationRepository.class);
        when(installations.findAll()).thenReturn(List.of(installation));
        var configurations = mock(VerifactuConfigurationRepository.class);
        var configuration = new VerifactuConfiguration(company.getId());
        configuration.changeMode(FiscalMode.VERIFACTU, Instant.now(), null);
        when(configurations.findByCompanyId(company.getId())).thenReturn(Optional.of(configuration));
        var record = mock(FiscalRecord.class);
        when(record.getId()).thenReturn(java.util.UUID.randomUUID());
        when(record.getSequence()).thenReturn(3L);
        when(record.getFiscalMode()).thenReturn(FiscalMode.VERIFACTU);
        var snapshot = Map.<String, Object>of("total", new BigDecimal("12.10"));
        when(record.getSnapshot()).thenReturn(snapshot);
        when(record.getSnapshotHash()).thenReturn(new FiscalJsonHasher().hash(snapshot));
        var records = mock(FiscalRecordRepository.class);
        when(records.findAllByCompanyIdAndInstallationIdOrderBySequence(company.getId(), installationId))
                .thenReturn(List.of(record));
        var events = mock(FiscalEventRepository.class);
        when(events.findAllByCompanyIdAndInstallationIdOrderBySequenceAsc(company.getId(), installationId))
                .thenReturn(List.of());
        var alarms = mock(FiscalAlarmRepository.class);
        var eventService = mock(FiscalEventService.class);
        var artifacts = mock(FiscalRecordArtifactRepository.class);
        when(artifacts.findByRecordId(record.getId())).thenReturn(Optional.empty());
        var snapshots = mock(FiscalPrintSnapshotRecordRepository.class);
        when(snapshots.findByRecordId(record.getId())).thenReturn(Optional.empty());
        var service = new FiscalIntegrityService(organization, installations, configurations,
                records, events, alarms, eventService, new FiscalQrUrlService());
        service.setArtifacts(artifacts);
        service.setPrintSnapshots(snapshots);

        var result = service.check();

        assertThat(result.anomalies()).containsExactlyInAnyOrder(
                "INTEGRIDAD_ARTEFACTO_AUSENTE_3", "INTEGRIDAD_SNAPSHOT_IMPRESION_AUSENTE_3");
    }

    @Test
    void alteredFullPrintSnapshotCreatesBlockingNoVerifactuAlarms() {
        var company = new Company("B12345674", "Empresa fiscal", Map.of(
                "linea1", "Calle 1", "ciudad", "Las Palmas", "codigoPostal", "35001",
                "provincia", "Las Palmas", "pais", "ES"));
        var installation = mock(Installation.class);
        var installationId = java.util.UUID.randomUUID();
        when(installation.getId()).thenReturn(installationId);
        var organization = mock(CurrentOrganization.class);
        when(organization.currentCompany()).thenReturn(company);
        var installations = mock(InstallationRepository.class);
        when(installations.findAll()).thenReturn(List.of(installation));
        var configurations = mock(VerifactuConfigurationRepository.class);
        var configuration = new VerifactuConfiguration(company.getId());
        configuration.changeMode(FiscalMode.NO_VERIFACTU, Instant.now(), null);
        when(configurations.findByCompanyId(company.getId())).thenReturn(Optional.of(configuration));

        var snapshotMap = Map.<String, Object>of("total", new BigDecimal("12.10"));
        var record = new FiscalRecord(
                java.util.UUID.randomUUID(), company.getId(), installationId,
                java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), 1,
                FiscalRecordOperation.ALTA, FiscalDocumentType.F2, "001-260825-000001",
                LocalDate.of(2026, 8, 25), Instant.parse("2026-08-25T10:00:00Z"),
                "Atlantic/Canary", "B12345674", new BigDecimal("2.10"),
                new BigDecimal("12.10"), null, "A".repeat(64),
                new FiscalJsonHasher().hash(snapshotMap), snapshotMap,
                "1.0", "SHA-256", "4.2.7", FiscalMode.NO_VERIFACTU);
        var qrUrl = new FiscalQrUrlService().url(
                record, FiscalMode.NO_VERIFACTU, FiscalEndpointEnvironment.TEST);
        var alteredPrint = new FiscalPrintSnapshot(
                "ALTERED", "other-build", FiscalMode.NO_VERIFACTU,
                FiscalEndpointEnvironment.TEST, qrUrl, "F".repeat(64),
                "QR alterado:", null, FiscalPrintSnapshotFactory.TEST_NOTICE);
        var signedXml = "<signed/>";
        var artifact = new FiscalRecordArtifact(
                record.getId(), FiscalMode.NO_VERIFACTU, FiscalEndpointEnvironment.TEST,
                true, java.util.UUID.randomUUID(), "Empresa fiscal", "B12345674",
                "<unsigned/>", signedXml, "CERT", sha256(signedXml), alteredPrint,
                Instant.parse("2026-08-25T10:00:01Z"));

        var records = mock(FiscalRecordRepository.class);
        when(records.findAllByCompanyIdAndInstallationIdOrderBySequence(
                company.getId(), installationId)).thenReturn(List.of(record));
        var events = mock(FiscalEventRepository.class);
        when(events.findAllByCompanyIdAndInstallationIdOrderBySequenceAsc(
                company.getId(), installationId)).thenReturn(List.of());
        var artifacts = mock(FiscalRecordArtifactRepository.class);
        when(artifacts.findByRecordId(record.getId())).thenReturn(Optional.of(artifact));
        var printSnapshots = mock(FiscalPrintSnapshotRecordRepository.class);
        when(printSnapshots.findByRecordId(record.getId())).thenReturn(Optional.of(
                new FiscalPrintSnapshotRecord(record.getId(), alteredPrint,
                        Instant.parse("2026-08-25T10:00:01Z"))));
        var alarms = mock(FiscalAlarmRepository.class);
        var eventService = mock(FiscalEventService.class);
        var signer = mock(FiscalXadesSigner.class);
        when(signer.verifySignedXml(signedXml, "CERT")).thenReturn(true);
        var service = new FiscalIntegrityService(
                organization, installations, configurations, records, events, alarms,
                eventService, new FiscalQrUrlService());
        service.setArtifacts(artifacts);
        service.setPrintSnapshots(printSnapshots);
        service.setSigner(signer);

        var result = service.check();

        assertThat(result.ok()).isFalse();
        assertThat(result.anomalies()).containsExactlyInAnyOrder(
                "INTEGRIDAD_SNAPSHOT_IMPRESION_QR_1",
                "INTEGRIDAD_SNAPSHOT_IMPRESION_METADATOS_1",
                "INTEGRIDAD_SNAPSHOT_IMPRESION_ARTEFACTO_1");
        verify(alarms, org.mockito.Mockito.times(3))
                .save(org.mockito.ArgumentMatchers.any(FiscalAlarm.class));
        verify(eventService).create(
                company.getId(), installationId, FiscalMode.NO_VERIFACTU,
                FiscalEventType.BILLING_ANOMALY_DETECTED,
                String.join(",", result.anomalies()));
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().withUpperCase().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }
}
