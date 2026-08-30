package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tpverp.backend.installation.InstallationRepository;
import com.tpverp.backend.licensing.LicenseRepository;
import com.tpverp.backend.organization.CurrentOrganization;
import java.sql.Timestamp;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

@ExtendWith(MockitoExtension.class)
class FiscalExportJobServiceTest {
    @Test
    void workerProducesManifestWithSingleBoundedQueryAndNoPerRecordLookup() throws Exception {
        var repository = mock(FiscalExportJobRepository.class);
        var jdbc = mock(NamedParameterJdbcTemplate.class);
        var job = job(FiscalExportKind.BILLING);
        when(repository.findByIdAndExecutionToken(any(UUID.class), any(UUID.class)))
                .thenReturn(Optional.of(job));
        when(repository.claimQueued(any(UUID.class), any(UUID.class), any(Instant.class))).thenReturn(1);

        var service = service(repository, jdbc);
        service.run(job.getId());

        assertThat(job.getStatus()).isEqualTo(FiscalExportJobStatus.COMPLETED);
        assertThat(job.getProcessed()).isZero();
        assertThat(job.getFilePath()).endsWith(".zip");
        verify(jdbc).query(anyString(), any(MapSqlParameterSource.class), any(RowCallbackHandler.class));
        java.nio.file.Files.deleteIfExists(java.nio.file.Path.of(job.getFilePath()));
    }

    @Test
    void workerPersistsFailureAndDoesNotLeavePartialFile() {
        var repository = mock(FiscalExportJobRepository.class);
        var jdbc = mock(NamedParameterJdbcTemplate.class);
        var job = job(FiscalExportKind.BILLING);
        when(repository.findByIdAndExecutionToken(any(UUID.class), any(UUID.class)))
                .thenReturn(Optional.of(job));
        when(repository.claimQueued(any(UUID.class), any(UUID.class), any(Instant.class))).thenReturn(1);
        doAnswer(invocation -> { throw new IllegalStateException("db unavailable"); })
                .when(jdbc).query(anyString(), any(MapSqlParameterSource.class), any(RowCallbackHandler.class));

        service(repository, jdbc).run(job.getId());

        assertThat(job.getStatus()).isEqualTo(FiscalExportJobStatus.FAILED);
        assertThat(job.getError()).isEqualTo("fiscal_export_failed");
        assertThat(job.getFilePath()).isNull();
    }

    @Test
    void requiredSubmissionWorkerCountsEachSignedRecordAndCompletes() throws Exception {
        var repository = mock(FiscalExportJobRepository.class);
        var jdbc = mock(NamedParameterJdbcTemplate.class);
        var submissions = mock(FiscalRequiredSubmissionRepository.class);
        var evidence = mock(FiscalExportJobEvidenceService.class);
        var requirementId = UUID.randomUUID();
        var job = new FiscalExportJob(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "user",
                new FiscalExportJobRequest(FiscalExportKind.BILLING,
                        java.time.OffsetDateTime.parse("2026-08-01T00:00:00Z"),
                        java.time.OffsetDateTime.parse("2026-08-31T23:59:59Z"), List.of(), null, null,
                        null, null, null, null, null, FiscalExportJobScope.PERIOD),
                FiscalMode.NO_VERIFACTU, 1, Instant.now(), Instant.now().plusSeconds(3600));
        job.attachRequiredSubmission(requirementId);
        var requirement = new FiscalRequiredSubmission(job.getCompanyId(), job.getInstallationId(),
                "REQ-001", Instant.now());
        var signedXml = "<sf:RegistroAlta xmlns:sf=\"https://www2.agenciatributaria.gob.es/static_files/common/"
                + "internet/dep/aplicaciones/es/aeat/tike/cont/ws/SuministroInformacion.xsd\" "
                + "xmlns:ds=\"http://www.w3.org/2000/09/xmldsig#\"><ds:Signature/></sf:RegistroAlta>";
        var signedHash = java.util.HexFormat.of().withUpperCase().formatHex(
                java.security.MessageDigest.getInstance("SHA-256")
                        .digest(signedXml.getBytes(StandardCharsets.UTF_8)));
        var row = mock(java.sql.ResultSet.class);
        when(row.getString("xml")).thenReturn(signedXml);
        when(row.getString("artifact_xml_hash")).thenReturn(signedHash);
        when(row.getString("artifact_issuer_name")).thenReturn("Empresa");
        when(row.getString("artifact_issuer_tax_id")).thenReturn("B12345678");
        when(row.getString("nif_emisor")).thenReturn("B12345678");
        when(row.getString("huella")).thenReturn("A".repeat(64));
        when(row.getObject("id", UUID.class)).thenReturn(UUID.randomUUID());
        when(row.getLong("secuencia")).thenReturn(1L);
        when(row.getString("serie_numero")).thenReturn("F-1");
        when(row.getObject("fecha_expedicion", java.time.LocalDate.class))
                .thenReturn(java.time.LocalDate.of(2026, 8, 1));
        when(row.getTimestamp("generado_en"))
                .thenReturn(java.sql.Timestamp.from(Instant.parse("2026-08-01T10:00:00Z")));
        when(row.getString("zona_horaria")).thenReturn("UTC");
        when(row.getString("operacion")).thenReturn("ALTA");
        when(row.getBigDecimal("cuota_total")).thenReturn(new java.math.BigDecimal("2.10"));
        when(row.getBigDecimal("importe_total")).thenReturn(new java.math.BigDecimal("12.10"));
        when(repository.claimQueued(any(UUID.class), any(UUID.class), any(Instant.class))).thenReturn(1);
        when(repository.findByIdAndExecutionToken(any(UUID.class), any(UUID.class)))
                .thenReturn(Optional.of(job));
        when(repository.updateProgress(any(UUID.class), any(UUID.class), anyLong(), anyBoolean(), any(Instant.class)))
                .thenReturn(1);
        when(submissions.findByIdAndCompanyIdAndInstallationId(requirementId,
                job.getCompanyId(), job.getInstallationId())).thenReturn(Optional.of(requirement));
        when(evidence.registerEvidenceAndCompleteJob(any(FiscalExportJob.class), any(), any(),
                anyString(), anyLong(), any(Instant.class), anyString(), anyLong(), any(Instant.class)))
                .thenAnswer(invocation -> {
                    var current = invocation.getArgument(0, FiscalExportJob.class);
                    current.markCompleted(invocation.getArgument(6, String.class),
                            invocation.getArgument(7, Long.class), invocation.getArgument(4, Long.class),
                            false, invocation.getArgument(5, Instant.class),
                            invocation.getArgument(8, Instant.class));
                    return null;
                });
        org.mockito.Mockito.doAnswer(invocation -> {
            ((RowCallbackHandler) invocation.getArgument(2)).processRow(row);
            return null;
        }).when(jdbc).query(anyString(), any(MapSqlParameterSource.class), any(RowCallbackHandler.class));

        var service = new FiscalExportJobService(mock(CurrentOrganization.class),
                mock(InstallationRepository.class), mock(LicenseRepository.class), repository, jdbc,
                evidence, submissions,
                java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), "tpv-erp", "fiscal-exports").toString(),
                1_000_000L, 2_000_000_000L, mock(VerifactuOfficialXsdValidator.class));
        service.run(job.getId());

        assertThat(job.getStatus()).isEqualTo(FiscalExportJobStatus.COMPLETED);
        assertThat(job.getProcessed()).isEqualTo(1);
        verify(repository).updateProgress(any(UUID.class), any(UUID.class), org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq(false), any(Instant.class));
        java.nio.file.Files.deleteIfExists(java.nio.file.Path.of(job.getFilePath()));
    }

    @Test
    void requiredSubmissionWorkerSplits1001RecordsIntoTwoOfficialEnvelopes() throws Exception {
        var repository = mock(FiscalExportJobRepository.class);
        var jdbc = mock(NamedParameterJdbcTemplate.class);
        var submissions = mock(FiscalRequiredSubmissionRepository.class);
        var evidence = mock(FiscalExportJobEvidenceService.class);
        var validator = mock(VerifactuOfficialXsdValidator.class);
        var requirementId = UUID.randomUUID();
        var job = new FiscalExportJob(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "user",
                new FiscalExportJobRequest(FiscalExportKind.BILLING,
                        java.time.OffsetDateTime.parse("2026-08-01T00:00:00Z"),
                        java.time.OffsetDateTime.parse("2026-08-31T23:59:59Z"), List.of(), null, null,
                        null, null, null, null, null, FiscalExportJobScope.PERIOD),
                FiscalMode.NO_VERIFACTU, 1_001, Instant.now(), Instant.now().plusSeconds(3600));
        job.attachRequiredSubmission(requirementId);
        var requirement = new FiscalRequiredSubmission(job.getCompanyId(), job.getInstallationId(),
                "REQ-1001", Instant.now());
        var signedXml = "<sf:RegistroAlta xmlns:sf=\"https://www2.agenciatributaria.gob.es/static_files/common/"
                + "internet/dep/aplicaciones/es/aeat/tike/cont/ws/SuministroInformacion.xsd\" "
                + "xmlns:ds=\"http://www.w3.org/2000/09/xmldsig#\"><ds:Signature/></sf:RegistroAlta>";
        var signedHash = java.util.HexFormat.of().withUpperCase().formatHex(
                java.security.MessageDigest.getInstance("SHA-256")
                        .digest(signedXml.getBytes(StandardCharsets.UTF_8)));
        var rows = new java.sql.ResultSet[1_001];
        for (var i = 0; i < rows.length; i++) rows[i] = requiredRow(i + 1, signedXml, signedHash);

        when(repository.claimQueued(any(UUID.class), any(UUID.class), any(Instant.class))).thenReturn(1);
        when(repository.findByIdAndExecutionToken(any(UUID.class), any(UUID.class)))
                .thenReturn(Optional.of(job));
        when(repository.updateProgress(any(UUID.class), any(UUID.class), anyLong(), anyBoolean(), any(Instant.class)))
                .thenReturn(1);
        when(submissions.findByIdAndCompanyIdAndInstallationId(requirementId,
                job.getCompanyId(), job.getInstallationId())).thenReturn(Optional.of(requirement));
        when(evidence.registerEvidenceAndCompleteJob(any(FiscalExportJob.class), any(), any(),
                anyString(), anyLong(), any(Instant.class), anyString(), anyLong(), any(Instant.class)))
                .thenAnswer(invocation -> {
                    var current = invocation.getArgument(0, FiscalExportJob.class);
                    current.markCompleted(invocation.getArgument(6, String.class),
                            invocation.getArgument(7, Long.class), invocation.getArgument(4, Long.class),
                            false, invocation.getArgument(5, Instant.class),
                            invocation.getArgument(8, Instant.class));
                    return null;
                });
        var queryNumber = new AtomicInteger();
        doAnswer(invocation -> {
            var callback = invocation.getArgument(2, RowCallbackHandler.class);
            var call = queryNumber.getAndIncrement();
            var from = call == 0 ? 0 : call == 1 ? 500 : 1_000;
            var to = call == 0 ? 501 : call == 1 ? 1_001 : 1_001;
            for (var i = from; i < to; i++) callback.processRow(rows[i]);
            return null;
        }).when(jdbc).query(anyString(), any(MapSqlParameterSource.class), any(RowCallbackHandler.class));

        var directory = java.nio.file.Path.of(System.getProperty("java.io.tmpdir"),
                "tpv-erp", "fiscal-exports");
        var service = new FiscalExportJobService(mock(CurrentOrganization.class),
                mock(InstallationRepository.class), mock(LicenseRepository.class), repository, jdbc,
                evidence, submissions, directory.toString(), 1_000_000L, 2_000_000_000L, validator);
        service.run(job.getId());

        assertThat(job.getStatus()).isEqualTo(FiscalExportJobStatus.COMPLETED);
        assertThat(job.getProcessed()).isEqualTo(1_001);
        assertThat(queryNumber.get()).isEqualTo(3);
        verify(validator, org.mockito.Mockito.times(2)).validate(any(java.nio.file.Path.class));
        try (var zip = new ZipFile(job.getFilePath())) {
            var first = new String(zip.getInputStream(zip.getEntry("requerimiento-aeat-000001.xml")).readAllBytes(),
                    StandardCharsets.UTF_8);
            var last = new String(zip.getInputStream(zip.getEntry("requerimiento-aeat-000002.xml")).readAllBytes(),
                    StandardCharsets.UTF_8);
            var manifest = new String(zip.getInputStream(zip.getEntry("manifest.json")).readAllBytes(),
                    StandardCharsets.UTF_8);
            assertThat(first).contains("<sf:RefRequerimiento>REQ-1001</sf:RefRequerimiento>")
                    .contains("<sf:FinRequerimiento>N</sf:FinRequerimiento>");
            assertThat(last).contains("<sf:RefRequerimiento>REQ-1001</sf:RefRequerimiento>")
                    .contains("<sf:FinRequerimiento>S</sf:FinRequerimiento>");
            assertThat(first.split("<sfLR:RegistroFactura>", -1).length - 1).isEqualTo(1_000);
            assertThat(last.split("<sfLR:RegistroFactura>", -1).length - 1).isEqualTo(1);
            assertThat(manifest).contains("\"files\": 2").contains("\"envelopeCount\": 2");
        } finally {
            java.nio.file.Files.deleteIfExists(java.nio.file.Path.of(job.getFilePath()));
        }
    }

    @Test
    void restartRecoveryRequeuesRunningJobsWithoutChangingCompletedMetadata() {
        var repository = mock(FiscalExportJobRepository.class);
        var service = service(repository, mock(NamedParameterJdbcTemplate.class));

        service.requeueInterruptedJobs();

        verify(repository).findTop100ByStatusAndUpdatedAtBeforeOrderByCreatedAtAsc(
                org.mockito.ArgumentMatchers.eq(FiscalExportJobStatus.RUNNING), any(Instant.class));
    }

    @Test
    void tokenCleanupBindsInstantAsTimestampJdbc() {
        var repository = mock(FiscalExportJobRepository.class);
        var jdbc = mock(NamedParameterJdbcTemplate.class);
        var now = Instant.parse("2026-08-28T19:00:00Z");
        var parameters = ArgumentCaptor.forClass(MapSqlParameterSource.class);

        service(repository, jdbc).expireJobs(now);

        verify(jdbc).update(anyString(), parameters.capture());
        assertThat(parameters.getValue().getValue("now")).isEqualTo(Timestamp.from(now));
    }

    @Test
    void expiredJobIsMarkedExpiredAndItsFileIsRemoved() throws Exception {
        var repository = mock(FiscalExportJobRepository.class);
        var jdbc = mock(NamedParameterJdbcTemplate.class);
        var job = job(FiscalExportKind.EVENTS);
        var directory = java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), "tpv-erp", "fiscal-exports");
        java.nio.file.Files.createDirectories(directory);
        var file = java.nio.file.Files.createTempFile(directory, "fiscal-test-", ".zip");
        job.markCompleted(file.toString(), 1, 0, false, Instant.now());
        var expired = new FiscalExportJob(job.getCompanyId(), job.getStoreId(), job.getInstallationId(),
                job.getRequestedBy(), request(FiscalExportKind.EVENTS), FiscalMode.PRE_SIF, 0,
                Instant.now().minusSeconds(100), Instant.now().minusSeconds(1));
        expired.markCompleted(file.toString(), 1, 0, false, Instant.now());
        when(repository.findTop100ByExpiresAtBeforeAndStatusInOrderByExpiresAtAsc(
                any(Instant.class), any())).thenReturn(List.of(expired));
        when(repository.expireIfEligible(any(UUID.class), any(Instant.class))).thenReturn(1);
        service(repository, jdbc).expireJobs(Instant.now());

        assertThat(expired.getStatus()).isEqualTo(FiscalExportJobStatus.EXPIRED);
        assertThat(java.nio.file.Files.exists(file)).isFalse();
    }

    @Test
    void runningJobCannotBeExpiredByCleanupRace() {
        var repository = mock(FiscalExportJobRepository.class);
        var job = job(FiscalExportKind.BILLING);
        job.markRunning(Instant.now());

        service(repository, mock(NamedParameterJdbcTemplate.class)).expireIfNecessary(job);

        verify(repository, org.mockito.Mockito.never())
                .expireIfEligible(any(UUID.class), any(Instant.class));
        assertThat(job.getStatus()).isEqualTo(FiscalExportJobStatus.RUNNING);
    }

    private static FiscalExportJobService service(FiscalExportJobRepository repository,
            NamedParameterJdbcTemplate jdbc) {
        var evidence = mock(FiscalExportJobEvidenceService.class);
        org.mockito.Mockito.lenient().doAnswer(invocation -> {
            var job = invocation.getArgument(0, FiscalExportJob.class);
            job.markCompleted(invocation.getArgument(6, String.class),
                    invocation.getArgument(7, Long.class), invocation.getArgument(4, Long.class),
                    false, invocation.getArgument(5, Instant.class),
                    invocation.getArgument(8, Instant.class));
            return null;
        }).when(evidence).registerEvidenceAndCompleteJob(any(FiscalExportJob.class), any(), any(),
                anyString(), any(Long.class), any(Instant.class), anyString(), any(Long.class), any(Instant.class));
        return new FiscalExportJobService(mock(CurrentOrganization.class),
                mock(InstallationRepository.class), mock(LicenseRepository.class), repository, jdbc,
                evidence);
    }

    private static FiscalExportJob job(FiscalExportKind kind) {
        var id = UUID.randomUUID();
        return new FiscalExportJob(id, id, id, "user", request(kind), FiscalMode.PRE_SIF, 1,
                Instant.now(), Instant.now().plusSeconds(3600));
    }

    private static FiscalExportJobRequest request(FiscalExportKind kind) {
        return new FiscalExportJobRequest(kind, null, null, List.of(), null, null, null,
                null, null, null, null);
    }

    private static java.sql.ResultSet requiredRow(long sequence, String signedXml, String signedHash)
            throws java.sql.SQLException {
        var row = mock(java.sql.ResultSet.class);
        when(row.getString("xml")).thenReturn(signedXml);
        when(row.getString("artifact_xml_hash")).thenReturn(signedHash);
        when(row.getString("artifact_issuer_name")).thenReturn("Empresa");
        when(row.getString("artifact_issuer_tax_id")).thenReturn("B12345678");
        when(row.getString("nif_emisor")).thenReturn("B12345678");
        when(row.getString("huella")).thenReturn("A".repeat(64));
        when(row.getObject("id", UUID.class)).thenReturn(UUID.randomUUID());
        when(row.getLong("secuencia")).thenReturn(sequence);
        when(row.getString("serie_numero")).thenReturn("F-" + sequence);
        when(row.getObject("fecha_expedicion", java.time.LocalDate.class))
                .thenReturn(java.time.LocalDate.of(2026, 8, 1));
        when(row.getTimestamp("generado_en"))
                .thenReturn(java.sql.Timestamp.from(Instant.parse("2026-08-01T10:00:00Z")));
        when(row.getString("zona_horaria")).thenReturn("UTC");
        when(row.getString("operacion")).thenReturn("ALTA");
        when(row.getBigDecimal("cuota_total")).thenReturn(new java.math.BigDecimal("2.10"));
        when(row.getBigDecimal("importe_total")).thenReturn(new java.math.BigDecimal("12.10"));
        return row;
    }
}
