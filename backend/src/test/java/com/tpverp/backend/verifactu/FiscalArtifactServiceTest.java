package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.eq;

import com.tpverp.backend.installation.Installation;
import com.tpverp.backend.installation.InstallationRepository;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.CompanyRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(MockitoExtension.class)
class FiscalArtifactServiceTest {

    @Mock private FiscalRecordArtifactRepository artifacts;
    @Mock private FiscalSystemVersionRepository systemVersions;
    @Mock private FiscalPrintSnapshotRecordRepository printSnapshots;
    @Mock private CompanyRepository companies;
    @Mock private InstallationRepository installations;
    @Mock private VerifactuXmlService xml;
    @Mock private FiscalQrUrlService qrUrls;
    @Mock private FiscalPrintSnapshotFactory snapshots;
    @Mock private FiscalRuntimeProperties runtime;
    @Mock private FiscalXadesSigner signer;

    private FiscalArtifactService service;
    private FiscalRecord record;
    private Company company;
    private Installation installation;

    @BeforeEach
    void setUp() {
        record = record();
        company = new Company("B12345674", "Empresa fiscal", address());
        installation = new Installation("INST-DEV-001", "public", Instant.parse("2026-01-01T00:00:00Z"));
        lenient().when(artifacts.existsById(record.getId())).thenReturn(false);
        lenient().when(printSnapshots.existsById(record.getId())).thenReturn(false);
        lenient().when(companies.findById(record.getCompanyId())).thenReturn(Optional.of(company));
        lenient().when(installations.findById(record.getInstallationId())).thenReturn(Optional.of(installation));
        lenient().when(systemVersions.findByCompanyIdAndInstallationIdAndSystemVersionAndInstallationNumberAndReleaseId(
                record.getCompanyId(), record.getInstallationId(), record.getApplicationVersion(),
                installation.getReferencia(), "LEGACY-RUNTIME")).thenReturn(Optional.empty());
        lenient().when(systemVersions.save(any(FiscalSystemVersion.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(xml.recordXml(any(), any())).thenReturn("<registro/>");
        lenient().when(runtime.endpointEnvironment()).thenReturn(FiscalEndpointEnvironment.TEST);
        lenient().when(runtime.isSandbox()).thenReturn(true);
        lenient().when(runtime.declarationHash()).thenReturn("E".repeat(64));
        lenient().when(runtime.systemVersion()).thenReturn(record.getApplicationVersion());
        lenient().when(snapshots.create(any(), eq(FiscalMode.VERIFACTU),
                eq(FiscalEndpointEnvironment.TEST), eq(record.getApplicationVersion())))
                .thenReturn(snapshot());
        service = new FiscalArtifactService(artifacts, systemVersions, printSnapshots,
                companies, installations, xml, qrUrls, snapshots, runtime, signer,
                "Fabricante ERP", "B12345674", "SIF ERP", "SIF-01");
    }

    @Test
    void enlazaArtefactoConLaVersionDeSistemaPersistida() {
        service.create(record);

        var version = ArgumentCaptor.forClass(FiscalSystemVersion.class);
        verify(systemVersions).save(version.capture());
        assertThat(version.getValue().getProducerName()).isEqualTo("Fabricante ERP");
        assertThat(version.getValue().getSystemVersion()).isEqualTo("4.2.0");
        assertThat(version.getValue().getInstallationNumber()).isEqualTo("INST-DEV-001");
        assertThat(version.getValue().getDeclarationHash()).isEqualTo("E".repeat(64));

        var artifact = ArgumentCaptor.forClass(FiscalRecordArtifact.class);
        verify(artifacts).save(artifact.capture());
        assertThat(artifact.getValue().getSystemVersionId()).isEqualTo(version.getValue().getId());
        assertThat(artifact.getValue().getIssuerName()).isEqualTo("Empresa fiscal");
        assertThat(artifact.getValue().getIssuerTaxId()).isEqualTo("B12345674");
        assertThat(artifact.getValue().getIssuerAddress()).containsEntry(
                "linea1", "Calle 1");
        assertThat(artifact.getValue().getQrUrl()).isEqualTo(snapshot().qrUrl());
        verify(printSnapshots).save(any(FiscalPrintSnapshotRecord.class));
    }

    @Test
    void rechazaReutilizarVersionConIdentidadDistinta() {
        var existing = new FiscalSystemVersion(record.getCompanyId(), record.getInstallationId(),
                "B12345674", "Otro fabricante", "SIF ERP", "SIF-01", "4.2.0",
                "INST-DEV-001", null, true, Instant.parse("2026-08-22T10:00:00Z"));
        when(systemVersions.findByCompanyIdAndInstallationIdAndSystemVersionAndInstallationNumberAndReleaseId(
                record.getCompanyId(), record.getInstallationId(), record.getApplicationVersion(),
                installation.getReferencia(), "LEGACY-RUNTIME")).thenReturn(Optional.of(existing));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.create(record))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("identidad fiscal");
    }

    @Test
    void rechazaReutilizarVersionConOtraDeclaracionResponsable() {
        var existing = new FiscalSystemVersion(record.getCompanyId(), record.getInstallationId(),
                "B12345674", "Fabricante ERP", "SIF ERP", "SIF-01", "4.2.0",
                "INST-DEV-001", "F".repeat(64), true,
                Instant.parse("2026-08-22T10:00:00Z"));
        when(systemVersions.findByCompanyIdAndInstallationIdAndSystemVersionAndInstallationNumberAndReleaseId(
                record.getCompanyId(), record.getInstallationId(), record.getApplicationVersion(),
                installation.getReferencia(), "LEGACY-RUNTIME")).thenReturn(Optional.of(existing));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.create(record))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("identidad fiscal");
    }

    @Test
    void realReusesIdentityWithTheSameSidecarHashAndRejectsAChangedArtifact() {
        var artifactHash = "A".repeat(64);
        var manifest = new FiscalReleaseManifest(
                "tpv-erp-4.2.0", "4.2.0", FiscalProductCapability.VERIFACTU_ONLY,
                "V229", "abcdef1", null, null);
        var existing = new FiscalSystemVersion(record.getCompanyId(), record.getInstallationId(),
                "B12345674", "Fabricante ERP", "SIF ERP", "SIF-01", "4.2.0",
                "INST-DEV-001", "E".repeat(64), false,
                Instant.parse("2026-08-22T10:00:00Z"), manifest.releaseId(), artifactHash,
                manifest.commitHash(), manifest.capability(), manifest.schemaVersion(),
                manifest.manifestHash());
        when(runtime.isSandbox()).thenReturn(false);
        when(runtime.releaseManifest()).thenReturn(manifest);
        when(runtime.productCapability()).thenReturn(FiscalProductCapability.VERIFACTU_ONLY);
        when(runtime.resolvedArtifactHash()).thenReturn(artifactHash);
        when(snapshots.create(any(), eq(FiscalMode.VERIFACTU),
                eq(FiscalEndpointEnvironment.TEST), eq(record.getApplicationVersion())))
                .thenReturn(snapshot());
        when(systemVersions.findByCompanyIdAndInstallationIdAndSystemVersionAndInstallationNumberAndReleaseId(
                record.getCompanyId(), record.getInstallationId(), record.getApplicationVersion(),
                installation.getReferencia(), manifest.releaseId())).thenReturn(Optional.empty(), Optional.of(existing),
                        Optional.of(existing));

        service.create(record);
        service.create(recordWithSameFiscalScope());

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> {
                    when(runtime.resolvedArtifactHash()).thenReturn("B".repeat(64));
                    service.create(recordWithSameFiscalScope());
                })
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("release");
        verify(systemVersions, org.mockito.Mockito.times(1)).save(any(FiscalSystemVersion.class));
    }

    @Test
    void createsDistinctIdentitiesForTheSamePublicVersionAcrossReleases() {
        var first = new FiscalReleaseManifest(
                "release-4.2.0-a", "4.2.0", FiscalProductCapability.DUAL,
                "V229", 1, 10, "abcdef1", null, null);
        var second = new FiscalReleaseManifest(
                "release-4.2.0-b", "4.2.0", FiscalProductCapability.DUAL,
                "V229", 2, 1, "abcdef2", null, null);
        when(runtime.releaseManifest()).thenReturn(first);
        when(runtime.productCapability()).thenReturn(FiscalProductCapability.DUAL);
        when(systemVersions
                .findByCompanyIdAndInstallationIdAndSystemVersionAndInstallationNumberAndReleaseId(
                        record.getCompanyId(), record.getInstallationId(), "4.2.0",
                        installation.getReferencia(), first.releaseId()))
                .thenReturn(Optional.empty());
        when(systemVersions
                .findByCompanyIdAndInstallationIdAndSystemVersionAndInstallationNumberAndReleaseId(
                        record.getCompanyId(), record.getInstallationId(), "4.2.0",
                        installation.getReferencia(), second.releaseId()))
                .thenReturn(Optional.empty());

        service.create(record);
        when(runtime.releaseManifest()).thenReturn(second);
        service.create(recordWithSameFiscalScope());

        var versions = ArgumentCaptor.forClass(FiscalSystemVersion.class);
        verify(systemVersions, org.mockito.Mockito.times(2)).save(versions.capture());
        assertThat(versions.getAllValues()).extracting(FiscalSystemVersion::getSystemVersion)
                .containsExactly("4.2.0", "4.2.0");
        assertThat(versions.getAllValues()).extracting(FiscalSystemVersion::getReleaseId)
                .containsExactly(first.releaseId(), second.releaseId());
    }

    @Test
    void noCreaArtefactoFiscalParaCompatibilidadPreSif() {
        var legacy = new FiscalRecord(record.chainId(), record.getCompanyId(),
                record.getInstallationId(), record.getStoreId(), record.getDocumentId(),
                record.getSequence(), record.getOperation(), record.getDocumentType(),
                record.getNumber(), record.getIssueDate(), record.getGeneratedAt(),
                record.getTimezone(), record.getIssuerTaxId(), record.getTotalTax(),
                record.getTotalAmount(), record.getPreviousHash(), record.getHash(),
                record.getSnapshotHash(), record.getSnapshot(), record.getFormatVersion(),
                record.getAlgorithmVersion(), record.getApplicationVersion(), FiscalMode.PRE_SIF);

        service.create(legacy);

        org.mockito.Mockito.verifyNoInteractions(artifacts, printSnapshots, companies,
                installations, systemVersions, xml, snapshots, signer);
    }

    @Test
    void anulacionCreaXmlFiscalSinQrNiSnapshotDeImpresion() {
        var cancellation = new FiscalRecord(
                record.chainId(), record.getCompanyId(), record.getInstallationId(),
                record.getStoreId(), record.getDocumentId(), 2,
                FiscalRecordOperation.ANULACION, record.getDocumentType(), record.getNumber(),
                record.getIssueDate(), Instant.parse("2026-08-23T10:05:00Z"),
                record.getTimezone(), record.getIssuerTaxId(), null, null,
                record.getHash(), "C".repeat(64), "D".repeat(64),
                Map.of("registroAnterior", Map.of("huella", record.getHash())),
                record.getFormatVersion(), record.getAlgorithmVersion(),
                record.getApplicationVersion(), FiscalMode.VERIFACTU);
        when(artifacts.existsById(cancellation.getId())).thenReturn(false);
        when(printSnapshots.existsById(cancellation.getId())).thenReturn(false);
        when(companies.findById(cancellation.getCompanyId())).thenReturn(Optional.of(company));
        when(installations.findById(cancellation.getInstallationId()))
                .thenReturn(Optional.of(installation));

        service.create(cancellation);

        verify(snapshots, never()).create(any(), any(), any(), any());
        verify(printSnapshots, never()).save(any());
        var artifact = ArgumentCaptor.forClass(FiscalRecordArtifact.class);
        verify(artifacts).save(artifact.capture());
        assertThat(artifact.getValue().getQrUrl()).isNull();
        assertThat(artifact.getValue().getQrHash()).isNull();
        assertThat(artifact.getValue().getQrPrefix()).isNull();
    }

    private static FiscalPrintSnapshot snapshot() {
        return new FiscalPrintSnapshot("AEAT_QR_0.5.0", "4.2.0", FiscalMode.VERIFACTU,
                FiscalEndpointEnvironment.TEST,
                "https://prewww2.aeat.es/wlpl/TIKE-CONT/ValidarQR?nif=B12345674",
                "A".repeat(64), "QR tributario:",
                FiscalPrintSnapshotFactory.VERIFACTU_LEGEND,
                FiscalPrintSnapshotFactory.TEST_NOTICE);
    }

    private static FiscalRecord record() {
        return new FiscalRecord(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), 1, FiscalRecordOperation.ALTA,
                FiscalDocumentType.F2, "001-260823-000001", LocalDate.of(2026, 8, 23),
                Instant.parse("2026-08-23T10:00:00Z"), "Atlantic/Canary", "B12345674",
                new BigDecimal("2.10"), new BigDecimal("12.10"), null, "A".repeat(64),
                "B".repeat(64), Map.of("total", new BigDecimal("12.10")), "VERIFACTU-1",
                "SHA-256", "4.2.0", FiscalMode.VERIFACTU);
    }

    private static Map<String, String> address() {
        var result = new LinkedHashMap<String, String>();
        result.put("linea1", "Calle 1");
        result.put("ciudad", "Las Palmas");
        result.put("codigoPostal", "35001");
        result.put("provincia", "Las Palmas");
        result.put("pais", "ES");
        return result;
    }

    private FiscalRecord recordWithSameFiscalScope() {
        return new FiscalRecord(UUID.randomUUID(), record.getCompanyId(), record.getInstallationId(),
                record.getStoreId(), UUID.randomUUID(), record.getSequence() + 1,
                record.getOperation(), record.getDocumentType(), "001-260823-000002",
                record.getIssueDate(), record.getGeneratedAt(), record.getTimezone(),
                record.getIssuerTaxId(), record.getTotalTax(), record.getTotalAmount(),
                record.getPreviousHash(), record.getHash(), record.getSnapshotHash(),
                record.getSnapshot(), record.getFormatVersion(), record.getAlgorithmVersion(),
                record.getApplicationVersion(), record.getFiscalMode());
    }
}
