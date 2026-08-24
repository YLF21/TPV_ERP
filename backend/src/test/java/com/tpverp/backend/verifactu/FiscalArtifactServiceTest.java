package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

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
        lenient().when(systemVersions.findByCompanyIdAndInstallationIdAndSystemVersionAndInstallationNumber(
                record.getCompanyId(), record.getInstallationId(), record.getApplicationVersion(),
                installation.getReferencia())).thenReturn(Optional.empty());
        lenient().when(systemVersions.save(any(FiscalSystemVersion.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(xml.recordXml(any(), any())).thenReturn("<registro/>");
        lenient().when(runtime.endpointEnvironment()).thenReturn(FiscalEndpointEnvironment.TEST);
        lenient().when(runtime.isSandbox()).thenReturn(true);
        lenient().when(snapshots.create(record, FiscalMode.VERIFACTU, FiscalEndpointEnvironment.TEST,
                record.getApplicationVersion())).thenReturn(snapshot());
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
        assertThat(version.getValue().getSystemVersion()).isEqualTo("4.2.7");
        assertThat(version.getValue().getInstallationNumber()).isEqualTo("INST-DEV-001");

        var artifact = ArgumentCaptor.forClass(FiscalRecordArtifact.class);
        verify(artifacts).save(artifact.capture());
        assertThat(artifact.getValue().getSystemVersionId()).isEqualTo(version.getValue().getId());
    }

    @Test
    void rechazaReutilizarVersionConIdentidadDistinta() {
        var existing = new FiscalSystemVersion(record.getCompanyId(), record.getInstallationId(),
                "B12345674", "Otro fabricante", "SIF ERP", "SIF-01", "4.2.7",
                "INST-DEV-001", null, true, Instant.parse("2026-08-22T10:00:00Z"));
        when(systemVersions.findByCompanyIdAndInstallationIdAndSystemVersionAndInstallationNumber(
                record.getCompanyId(), record.getInstallationId(), record.getApplicationVersion(),
                installation.getReferencia())).thenReturn(Optional.of(existing));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.create(record))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("identidad fiscal");
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

    private static FiscalPrintSnapshot snapshot() {
        return new FiscalPrintSnapshot("AEAT_QR_0.5.0", "4.2.7", FiscalMode.VERIFACTU,
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
                "SHA-256", "4.2.7", FiscalMode.VERIFACTU);
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
}
