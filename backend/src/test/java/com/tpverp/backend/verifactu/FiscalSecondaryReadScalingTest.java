package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tpverp.backend.installation.Installation;
import com.tpverp.backend.installation.InstallationRepository;
import com.tpverp.backend.licensing.License;
import com.tpverp.backend.licensing.LicenseRepository;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;

class FiscalSecondaryReadScalingTest {

    @Test
    void anomalyCollectorDeduplicatesRetainedCodesInConstantTimeAndCapsEvidence() {
        var collector = new FiscalIntegrityService.AnomalyCollector();
        for (var index = 0; index < 10_000; index++) {
            collector.add("INTEGRIDAD_SNAPSHOT_" + index);
        }
        for (var index = 0; index < 1_000; index++) {
            assertThat(collector.addUnique("INTEGRIDAD_SNAPSHOT_" + index)).isFalse();
        }

        assertThat(collector).hasSize(1_000);
        assertThat(collector.total()).isEqualTo(10_000);
        assertThat(collector.categoryTotal("billing")).isEqualTo(10_000);
    }

    @Test
    void integrityUsesBoundedKeysetReadsAndNeverLoadsTheLegacyGlobalList() {
        var company = company();
        var installationId = UUID.randomUUID();
        var installation = mock(Installation.class);
        when(installation.getId()).thenReturn(installationId);
        when(installation.getReferencia()).thenReturn("inst-001");
        var organization = mock(CurrentOrganization.class);
        when(organization.currentCompany()).thenReturn(company);
        when(organization.currentStore()).thenReturn(null);
        var installations = mock(InstallationRepository.class);
        when(installations.findById(installationId)).thenReturn(Optional.of(installation));
        var licenses = mock(LicenseRepository.class);
        var license = mock(License.class);
        when(license.getInstalacionId()).thenReturn(installationId);
        when(licenses.findActiveByCompanyId(company.getId())).thenReturn(List.of(license));
        var configurations = mock(VerifactuConfigurationRepository.class);
        when(configurations.findByCompanyId(company.getId())).thenReturn(Optional.empty());
        var record = mock(FiscalRecord.class);
        var snapshot = Map.<String, Object>of("total", new BigDecimal("12.10"));
        when(record.getId()).thenReturn(UUID.randomUUID());
        when(record.getSequence()).thenReturn(1L);
        when(record.getHash()).thenReturn("A".repeat(64));
        when(record.getFiscalMode()).thenReturn(FiscalMode.VERIFACTU);
        when(record.getSnapshot()).thenReturn(snapshot);
        when(record.getSnapshotHash()).thenReturn(new FiscalJsonHasher().hash(snapshot));
        var records = mock(FiscalRecordRepository.class);
        when(records.findTopByCompanyIdAndInstallationIdOrderBySequenceDesc(
                company.getId(), installationId)).thenReturn(Optional.of(record));
        when(records.findIntegrityBatch(
                company.getId(), installationId, 0L, 1L, Pageable.ofSize(500)))
                .thenReturn(List.of(record));
        when(records.findIntegrityBatch(
                company.getId(), installationId, 1L, 1L, Pageable.ofSize(500)))
                .thenReturn(List.of());
        var events = mock(FiscalEventRepository.class);
        when(events.findTopByCompanyIdAndInstallationIdOrderBySequenceDesc(
                company.getId(), installationId)).thenReturn(Optional.empty());
        when(events.findIntegrityBatch(
                company.getId(), installationId, 0L, 0L, Pageable.ofSize(500)))
                .thenReturn(List.of());
        var alarms = mock(FiscalAlarmRepository.class);
        var eventService = mock(FiscalEventService.class);

        var result = new FiscalIntegrityService(organization, installations, licenses,
                configurations, records, events, alarms, eventService,
                new FiscalQrUrlService()).check();

        assertThat(result.billingRecordsChecked()).isOne();
        assertThat(result.eventRecordsChecked()).isZero();
        verify(records, never()).findAllByCompanyIdAndInstallationIdOrderBySequence(
                company.getId(), installationId);
        verify(records).findIntegrityBatch(
                company.getId(), installationId, 0L, 1L, Pageable.ofSize(500));
    }

    @Test
    void validScanKeepsOnlyLaunchEvidenceAndDoesNotCreateAnAlarm() {
        var company = company();
        var installationId = UUID.randomUUID();
        var installation = mock(Installation.class);
        when(installation.getId()).thenReturn(installationId);
        var organization = mock(CurrentOrganization.class);
        when(organization.currentCompany()).thenReturn(company);
        when(organization.currentStore()).thenReturn(null);
        var installations = mock(InstallationRepository.class);
        when(installations.findById(installationId)).thenReturn(Optional.of(installation));
        var licenses = mock(LicenseRepository.class);
        var license = mock(License.class);
        when(license.getInstalacionId()).thenReturn(installationId);
        when(licenses.findActiveByCompanyId(company.getId())).thenReturn(List.of(license));
        var configurations = mock(VerifactuConfigurationRepository.class);
        var configuration = new VerifactuConfiguration(company.getId());
        configuration.changeMode(FiscalMode.NO_VERIFACTU, Instant.now(), null);
        when(configurations.findByCompanyId(company.getId())).thenReturn(Optional.of(configuration));
        var records = mock(FiscalRecordRepository.class);
        when(records.findTopByCompanyIdAndInstallationIdOrderBySequenceDesc(
                company.getId(), installationId)).thenReturn(Optional.empty());
        var events = mock(FiscalEventRepository.class);
        when(events.findTopByCompanyIdAndInstallationIdOrderBySequenceDesc(
                company.getId(), installationId)).thenReturn(Optional.empty());
        var alarms = mock(FiscalAlarmRepository.class);
        var eventService = mock(FiscalEventService.class);
        var scanEvents = mock(FiscalIntegrityScanEventRecorder.class);

        var result = new FiscalIntegrityService(organization, installations, licenses,
                configurations, records, events, alarms, eventService,
                new FiscalQrUrlService(), mock(FiscalRecordArtifactRepository.class),
                mock(FiscalPrintSnapshotRecordRepository.class), mock(FiscalXadesSigner.class),
                mock(FiscalSystemVersionRepository.class), scanEvents).check();

        assertThat(result.ok()).isTrue();
        assertThat(result.anomaliesTotal()).isZero();
        verify(scanEvents).recordStarted(company.getId(), installationId, FiscalMode.NO_VERIFACTU);
        verify(scanEvents).recordResult(company.getId(), installationId,
                FiscalMode.NO_VERIFACTU, null, null, null);
        verify(alarms, never()).save(any(FiscalAlarm.class));
        verify(eventService, never()).create(any(), any(), any(), any(), any());
    }

    @Test
    void eventTimestampUsesSignedXmlOffsetAndDetectsColumnMismatch() {
        var company = company();
        var installationId = UUID.randomUUID();
        var installation = mock(Installation.class);
        when(installation.getId()).thenReturn(installationId);
        when(installation.getReferencia()).thenReturn("inst-001");
        var organization = mock(CurrentOrganization.class);
        when(organization.currentCompany()).thenReturn(company);
        when(organization.currentStore()).thenReturn(null);
        var installations = mock(InstallationRepository.class);
        when(installations.findById(installationId)).thenReturn(Optional.of(installation));
        var licenses = mock(LicenseRepository.class);
        var license = mock(License.class);
        when(license.getInstalacionId()).thenReturn(installationId);
        when(licenses.findActiveByCompanyId(company.getId())).thenReturn(List.of(license));
        var configurations = mock(VerifactuConfigurationRepository.class);
        var configuration = new VerifactuConfiguration(company.getId());
        configuration.changeMode(FiscalMode.NO_VERIFACTU, Instant.now(), null);
        when(configurations.findByCompanyId(company.getId())).thenReturn(Optional.of(configuration));
        when(configurations.findForUpdateByCompanyId(company.getId()))
                .thenReturn(Optional.of(configuration));

        var signedXml = "<r:Evento xmlns:r=\"urn:test\"><r:FechaHoraHusoGenEvento>"
                + "2026-08-26T09:10:11+05:30</r:FechaHoraHusoGenEvento></r:Evento>";
        var system = new FiscalSystemVersion(company.getId(), installationId,
                "B00000000", "Producer", "TPV ERP", "TPVERP", "4.1.0",
                installation.getReferencia(), null, false, Instant.now());
        var event = new FiscalEvent(company.getId(), installationId, system.getId(), 1,
                FiscalEventType.BILLING_ANOMALY_SCAN_STARTED, FiscalMode.NO_VERIFACTU,
                Instant.parse("2026-08-26T03:40:12Z"), null, "wrong-hash",
                signedXml, signedXml, sha256(signedXml), Instant.now());
        var records = mock(FiscalRecordRepository.class);
        when(records.findTopByCompanyIdAndInstallationIdOrderBySequenceDesc(
                company.getId(), installationId)).thenReturn(Optional.empty());
        var events = mock(FiscalEventRepository.class);
        when(events.findTopByCompanyIdAndInstallationIdOrderBySequenceDesc(
                company.getId(), installationId)).thenReturn(Optional.of(event));
        when(events.findIntegrityBatch(company.getId(), installationId, 0L, 1L,
                Pageable.ofSize(500))).thenReturn(List.of(event));
        when(events.findIntegrityBatch(company.getId(), installationId, 1L, 1L,
                Pageable.ofSize(500))).thenReturn(List.of());
        var systems = mock(FiscalSystemVersionRepository.class);
        when(systems.findAllById(List.of(system.getId()))).thenReturn(List.of(system));
        var signer = mock(FiscalXadesSigner.class);
        when(signer.verifySignedXml(signedXml)).thenReturn(true);
        var eventService = mock(FiscalEventService.class);
        var alarms = mock(FiscalAlarmRepository.class);
        var scanEvents = new FiscalIntegrityScanEventRecorder(
                eventService, configurations, alarms);

        var result = new FiscalIntegrityService(organization, installations, licenses,
                configurations, records, events, alarms, eventService,
                new FiscalQrUrlService(), mock(FiscalRecordArtifactRepository.class),
                mock(FiscalPrintSnapshotRecordRepository.class), signer, systems, scanEvents).check();

        assertThat(result.anomalies()).contains("INTEGRIDAD_HUELLA_EVENTO_1");
        assertThat(result.ok()).isFalse();
        var detail = ArgumentCaptor.forClass(String.class);
        verify(eventService).create(eq(company.getId()), eq(installationId),
                eq(FiscalMode.NO_VERIFACTU), eq(FiscalEventType.EVENT_ANOMALY_DETECTED),
                detail.capture());
        assertThat(detail.getValue()).startsWith("events total=1;").hasSizeLessThanOrEqualTo(100);
        verify(alarms).save(any(FiscalAlarm.class));
    }

    @Test
    void defectiveListUsesScopedProjectionAndDatabaseLimit() {
        var company = company();
        var store = new Store(company, "001", "Tienda", company.getDomicilioFiscal(), "hash",
                "Atlantic/Canary", "EUR", "es-ES");
        var installationId = UUID.randomUUID();
        var installation = mock(Installation.class);
        when(installation.getId()).thenReturn(installationId);
        var license = mock(License.class);
        when(license.getLocalCompanyId()).thenReturn(company.getId());
        when(license.getInstalacionId()).thenReturn(installationId);
        var organization = mock(CurrentOrganization.class);
        when(organization.currentCompany()).thenReturn(company);
        when(organization.currentStore()).thenReturn(store);
        var installations = mock(InstallationRepository.class);
        when(installations.findById(installationId)).thenReturn(Optional.of(installation));
        var licenses = mock(LicenseRepository.class);
        when(licenses.findActiveByTiendaId(store.getId())).thenReturn(List.of(license));
        var states = mock(FiscalSubmissionStateRepository.class);
        var expected = mock(DefectiveFiscalRecordView.class);
        when(states.findDefectiveViews(any(), any(), any(), any(), any())).thenReturn(List.of(expected));
        var service = new DefectiveFiscalRecordService(states, mock(FiscalRecordRepository.class),
                organization, new FiscalQrUrlService(), null, installations, licenses);

        assertThat(service.list()).containsExactly(expected);
        verify(states).findDefectiveViews(company.getId(), store.getId(), installationId,
                List.of(FiscalSubmissionStatus.RECHAZADO, FiscalSubmissionStatus.DEFECTUOSO,
                        FiscalSubmissionStatus.ACEPTADO_CON_ERRORES), Pageable.ofSize(100));
        verify(states, never()).findAllByStatusInOrderByUpdatedAtDesc(any());
    }

    private static Company company() {
        return new Company("B12345674", "Empresa", Map.of(
                "linea1", "Calle 1", "ciudad", "Las Palmas", "codigoPostal", "35001",
                "provincia", "Las Palmas", "pais", "ES"));
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
