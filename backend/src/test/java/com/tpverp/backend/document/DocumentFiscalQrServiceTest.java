package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;

import com.tpverp.backend.verifactu.FiscalDocumentType;
import com.tpverp.backend.verifactu.FiscalEndpointEnvironment;
import com.tpverp.backend.verifactu.FiscalMode;
import com.tpverp.backend.verifactu.FiscalPrintSnapshotFactory;
import com.tpverp.backend.verifactu.FiscalQrUrlService;
import com.tpverp.backend.verifactu.FiscalRecord;
import com.tpverp.backend.verifactu.FiscalRecordOperation;
import com.tpverp.backend.verifactu.FiscalRecordRepository;
import com.tpverp.backend.verifactu.FiscalPrintSnapshotRecord;
import com.tpverp.backend.verifactu.FiscalPrintSnapshotRecordRepository;
import com.tpverp.backend.verifactu.FiscalRuntimeProperties;
import com.tpverp.backend.verifactu.FiscalRecordArtifact;
import com.tpverp.backend.verifactu.FiscalRecordArtifactRepository;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DocumentFiscalQrServiceTest {

    @Mock
    private FiscalRecordRepository records;

    @Test
    void returnsQrUrlForDocumentWithFiscalAlta() {
        var documentId = UUID.randomUUID();
        when(records.findByDocumentIdAndOperation(documentId, FiscalRecordOperation.ALTA))
                .thenReturn(Optional.of(record(documentId)));
        var service = new DocumentFiscalQrService(records, new FiscalQrUrlService());

        assertThat(service.qrUrl(documentId))
                .contains("ValidarQR?nif=B12345674&numserie=FV-001-26-000001");
    }

    @Test
    void returnsNullWhenDocumentHasNoFiscalAlta() {
        var documentId = UUID.randomUUID();
        when(records.findByDocumentIdAndOperation(documentId, FiscalRecordOperation.ALTA))
                .thenReturn(Optional.empty());
        var service = new DocumentFiscalQrService(records, new FiscalQrUrlService());

        assertThat(service.qrUrl(documentId)).isNull();
    }

    @Test
    void reprintUsesFrozenSnapshotEvenIfRuntimeEndpointChanges() {
        var documentId = UUID.randomUUID();
        var record = record(documentId);
        when(records.findByDocumentIdAndOperation(documentId, FiscalRecordOperation.ALTA))
                .thenReturn(Optional.of(record));
        var snapshots = mock(FiscalPrintSnapshotRecordRepository.class);
        var snapshot = mock(FiscalPrintSnapshotRecord.class);
        when(snapshot.getQrUrl()).thenReturn(
                "https://prewww2.aeat.es/wlpl/TIKE-CONT/ValidarQR?nif=B12345674"
                        + "&numserie=FV-001-26-000001&fecha=02-06-2026&importe=157.26");
        when(snapshots.findByRecordId(record.getId())).thenReturn(Optional.of(snapshot));
        var runtime = mock(FiscalRuntimeProperties.class);

        var service = new DocumentFiscalQrService(records, new FiscalQrUrlService(), null,
                runtime, snapshots);

        assertThat(service.qrUrl(documentId)).isEqualTo(snapshot.getQrUrl());
    }

    @Test
    void productionWiringDoesNotRecalculateQrForLegacyRecordWithoutSnapshot() {
        var documentId = UUID.randomUUID();
        var record = record(documentId);
        when(records.findByDocumentIdAndOperation(documentId, FiscalRecordOperation.ALTA))
                .thenReturn(Optional.of(record));
        var snapshots = mock(FiscalPrintSnapshotRecordRepository.class);
        when(snapshots.findByRecordId(record.getId())).thenReturn(Optional.empty());
        var artifacts = mock(com.tpverp.backend.verifactu.FiscalRecordArtifactRepository.class);

        var service = new DocumentFiscalQrService(records, new FiscalQrUrlService(), artifacts,
                mock(FiscalRuntimeProperties.class), snapshots);

        assertThat(service.qrUrl(documentId)).isNull();
    }

    @Test
    void printResolutionUsesAndValidatesTheFrozenSnapshot() throws Exception {
        var documentId = UUID.randomUUID();
        var record = record(documentId);
        var qrUrl = "https://prewww2.aeat.es/wlpl/TIKE-CONT/ValidarQR"
                + "?nif=B12345674&numserie=FV-001-26-000001"
                + "&fecha=02-06-2026&importe=157.26";
        when(records.findByDocumentIdAndOperation(documentId, FiscalRecordOperation.ALTA))
                .thenReturn(Optional.of(record));
        var snapshots = mock(FiscalPrintSnapshotRecordRepository.class);
        var snapshot = mock(FiscalPrintSnapshotRecord.class);
        when(snapshot.getQrUrl()).thenReturn(qrUrl);
        when(snapshot.getQrHash()).thenReturn(sha256(qrUrl));
        when(snapshot.getMode()).thenReturn(FiscalMode.VERIFACTU);
        when(snapshot.getEnvironment()).thenReturn(FiscalEndpointEnvironment.TEST);
        when(snapshot.getFormatVersion()).thenReturn("AEAT_QR_0.5.0");
        when(snapshot.getGeneratorVersion()).thenReturn("TPV-ERP-2026.08.25");
        when(snapshot.getPrefix()).thenReturn("Prefijo fiscal congelado:");
        when(snapshot.getLegend()).thenReturn("Leyenda fiscal congelada");
        when(snapshot.getTestNotice()).thenReturn("Aviso de pruebas congelado");
        when(snapshots.findByRecordId(record.getId())).thenReturn(Optional.of(snapshot));
        var artifacts = mock(FiscalRecordArtifactRepository.class);
        var artifact = mock(FiscalRecordArtifact.class);
        when(artifact.getIssuerName()).thenReturn("Obligado congelado SL");
        when(artifact.getIssuerTaxId()).thenReturn("B12345674");
        when(artifact.getIssuerAddress()).thenReturn(address("Calle congelada 1"));
        when(artifact.getFiscalMode()).thenReturn(FiscalMode.VERIFACTU);
        when(artifact.getEnvironment()).thenReturn(FiscalEndpointEnvironment.TEST);
        when(artifact.getQrUrl()).thenReturn(qrUrl);
        when(artifact.getQrHash()).thenReturn(sha256(qrUrl));
        when(artifacts.findByRecordId(record.getId())).thenReturn(Optional.of(artifact));
        var service = new DocumentFiscalQrService(
                records, new FiscalQrUrlService(), artifacts,
                mock(FiscalRuntimeProperties.class), snapshots);

        var firstPrint = service.resolveForPrint(documentId).orElseThrow();
        var reprint = service.resolveForPrint(documentId).orElseThrow();

        assertThat(firstPrint.url()).isEqualTo(qrUrl);
        assertThat(firstPrint.payloadSha256()).isEqualTo(sha256(qrUrl));
        assertThat(firstPrint.mode()).isEqualTo(FiscalMode.VERIFACTU);
        assertThat(firstPrint.environment()).isEqualTo(FiscalEndpointEnvironment.TEST);
        assertThat(firstPrint.formatVersion()).isEqualTo("AEAT_QR_0.5.0");
        assertThat(firstPrint.generatorVersion()).isEqualTo("TPV-ERP-2026.08.25");
        assertThat(firstPrint.prefix()).isEqualTo("Prefijo fiscal congelado:");
        assertThat(firstPrint.legend()).isEqualTo("Leyenda fiscal congelada");
        assertThat(firstPrint.testNotice()).isEqualTo("Aviso de pruebas congelado");
        assertThat(firstPrint.issuerName()).isEqualTo("Obligado congelado SL");
        assertThat(firstPrint.issuerTaxId()).isEqualTo("B12345674");
        assertThat(firstPrint.issuerAddress().get("linea1"))
                .isEqualTo("Calle congelada 1");
        assertThat(firstPrint.toView().hasFrozenIssuerIdentity()).isTrue();
        assertThat(reprint).isEqualTo(firstPrint);
    }

    @Test
    void fiscalPrintFailsClosedWhenHistoricalIssuerAddressWasNotFrozen() throws Exception {
        var documentId = UUID.randomUUID();
        var record = record(documentId);
        var qrUrl = "https://prewww2.aeat.es/wlpl/TIKE-CONT/ValidarQR"
                + "?nif=B12345674&numserie=FV-001-26-000001"
                + "&fecha=02-06-2026&importe=157.26";
        when(records.findByDocumentIdAndOperation(documentId, FiscalRecordOperation.ALTA))
                .thenReturn(Optional.of(record));
        var snapshots = mock(FiscalPrintSnapshotRecordRepository.class);
        var snapshot = mock(FiscalPrintSnapshotRecord.class);
        when(snapshot.getQrUrl()).thenReturn(qrUrl);
        when(snapshot.getQrHash()).thenReturn(sha256(qrUrl));
        when(snapshot.getMode()).thenReturn(FiscalMode.VERIFACTU);
        when(snapshot.getEnvironment()).thenReturn(FiscalEndpointEnvironment.TEST);
        when(snapshot.getFormatVersion()).thenReturn(FiscalPrintSnapshotFactory.FORMAT_VERSION);
        when(snapshot.getGeneratorVersion()).thenReturn("TPV-ERP-2026.08.25");
        when(snapshot.getPrefix()).thenReturn(FiscalPrintSnapshotFactory.PREFIX);
        when(snapshot.getLegend()).thenReturn(FiscalPrintSnapshotFactory.VERIFACTU_LEGEND);
        when(snapshot.getTestNotice()).thenReturn(FiscalPrintSnapshotFactory.TEST_NOTICE);
        when(snapshots.findByRecordId(record.getId())).thenReturn(Optional.of(snapshot));
        var artifacts = mock(FiscalRecordArtifactRepository.class);
        var artifact = mock(FiscalRecordArtifact.class);
        when(artifact.getIssuerName()).thenReturn("Obligado congelado SL");
        when(artifact.getIssuerTaxId()).thenReturn("B12345674");
        when(artifact.getIssuerAddress()).thenReturn(null);
        when(artifacts.findByRecordId(record.getId())).thenReturn(Optional.of(artifact));
        var service = new DocumentFiscalQrService(
                records, new FiscalQrUrlService(), artifacts,
                mock(FiscalRuntimeProperties.class), snapshots);

        assertThatThrownBy(() -> service.resolveForPrint(documentId))
                .isInstanceOf(FiscalQrUnavailableException.class)
                .extracting(error -> ((FiscalQrUnavailableException) error).reason())
                .isEqualTo(FiscalQrUnavailableException.Reason
                        .FROZEN_ISSUER_IDENTITY_MISSING);
    }

    @Test
    void fiscalPrintRejectsIncompleteFrozenPresentationMetadata() throws Exception {
        var documentId = UUID.randomUUID();
        var record = record(documentId);
        var qrUrl = "https://www2.agenciatributaria.gob.es/wlpl/TIKE-CONT/ValidarQR"
                + "?nif=B12345674&numserie=FV-1&fecha=02-06-2026&importe=157.26";
        when(records.findByDocumentIdAndOperation(documentId, FiscalRecordOperation.ALTA))
                .thenReturn(Optional.of(record));
        var snapshots = mock(FiscalPrintSnapshotRecordRepository.class);
        var snapshot = mock(FiscalPrintSnapshotRecord.class);
        when(snapshot.getQrUrl()).thenReturn(qrUrl);
        when(snapshot.getQrHash()).thenReturn(sha256(qrUrl));
        when(snapshot.getMode()).thenReturn(FiscalMode.VERIFACTU);
        when(snapshot.getEnvironment()).thenReturn(FiscalEndpointEnvironment.PRODUCTION);
        when(snapshot.getFormatVersion()).thenReturn(FiscalPrintSnapshotFactory.FORMAT_VERSION);
        when(snapshot.getGeneratorVersion()).thenReturn("TPV-ERP-2026.08.25");
        when(snapshot.getLegend()).thenReturn(FiscalPrintSnapshotFactory.VERIFACTU_LEGEND);
        when(snapshots.findByRecordId(record.getId())).thenReturn(Optional.of(snapshot));
        var service = new DocumentFiscalQrService(
                records, new FiscalQrUrlService(), null,
                mock(FiscalRuntimeProperties.class), snapshots);

        assertThatThrownBy(() -> service.resolveForPrint(documentId))
                .isInstanceOf(FiscalQrUnavailableException.class)
                .extracting(error -> ((FiscalQrUnavailableException) error).reason())
                .isEqualTo(FiscalQrUnavailableException.Reason.FROZEN_SNAPSHOT_INVALID);
    }

    @Test
    void fiscalPrintFailsInsteadOfRecalculatingWhenFrozenSnapshotIsMissing() {
        var documentId = UUID.randomUUID();
        var record = record(documentId);
        when(records.findByDocumentIdAndOperation(documentId, FiscalRecordOperation.ALTA))
                .thenReturn(Optional.of(record));
        var snapshots = mock(FiscalPrintSnapshotRecordRepository.class);
        when(snapshots.findByRecordId(record.getId())).thenReturn(Optional.empty());
        var artifacts = mock(com.tpverp.backend.verifactu.FiscalRecordArtifactRepository.class);
        var service = new DocumentFiscalQrService(
                records, new FiscalQrUrlService(), artifacts,
                mock(FiscalRuntimeProperties.class), snapshots);

        assertThatThrownBy(() -> service.resolveForPrint(documentId))
                .isInstanceOf(FiscalQrUnavailableException.class)
                .extracting(error -> ((FiscalQrUnavailableException) error).reason())
                .isEqualTo(FiscalQrUnavailableException.Reason.FROZEN_SNAPSHOT_MISSING);
    }

    @Test
    void fiscalPrintNeverAcceptsArtifactAsSubstituteForPrintSnapshot() {
        var documentId = UUID.randomUUID();
        var record = record(documentId);
        when(records.findByDocumentIdAndOperation(documentId, FiscalRecordOperation.ALTA))
                .thenReturn(Optional.of(record));
        var snapshots = mock(FiscalPrintSnapshotRecordRepository.class);
        when(snapshots.findByRecordId(record.getId())).thenReturn(Optional.empty());
        var artifacts = mock(com.tpverp.backend.verifactu.FiscalRecordArtifactRepository.class);
        var service = new DocumentFiscalQrService(
                records, new FiscalQrUrlService(), artifacts,
                mock(FiscalRuntimeProperties.class), snapshots);

        assertThatThrownBy(() -> service.resolveForPrint(documentId))
                .isInstanceOf(FiscalQrUnavailableException.class)
                .extracting(error -> ((FiscalQrUnavailableException) error).reason())
                .isEqualTo(FiscalQrUnavailableException.Reason.FROZEN_SNAPSHOT_MISSING);
        verifyNoInteractions(artifacts);
    }

    @Test
    void fiscalPrintDetectsAnyQrPayloadChangeAgainstFrozenHash() throws Exception {
        var documentId = UUID.randomUUID();
        var record = record(documentId);
        when(records.findByDocumentIdAndOperation(documentId, FiscalRecordOperation.ALTA))
                .thenReturn(Optional.of(record));
        var snapshots = mock(FiscalPrintSnapshotRecordRepository.class);
        var snapshot = mock(FiscalPrintSnapshotRecord.class);
        when(snapshot.getQrUrl()).thenReturn("https://prewww2.aeat.es/qr?importe=99.99");
        when(snapshot.getQrHash()).thenReturn(sha256("https://prewww2.aeat.es/qr?importe=10.00"));
        when(snapshots.findByRecordId(record.getId())).thenReturn(Optional.of(snapshot));
        var service = new DocumentFiscalQrService(
                records, new FiscalQrUrlService(), null,
                mock(FiscalRuntimeProperties.class), snapshots);

        assertThatThrownBy(() -> service.resolveForPrint(documentId))
                .isInstanceOf(FiscalQrUnavailableException.class)
                .extracting(error -> ((FiscalQrUnavailableException) error).reason())
                .isEqualTo(FiscalQrUnavailableException.Reason.FROZEN_SNAPSHOT_HASH_MISMATCH);
    }

    private static String sha256(String value) throws Exception {
        return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private FiscalRecord record(UUID documentId) {
        return new FiscalRecord(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                documentId, 1, FiscalRecordOperation.ALTA, FiscalDocumentType.F1,
                "FV-001-26-000001", LocalDate.of(2026, 6, 2),
                Instant.parse("2026-06-02T10:00:00Z"), "Atlantic/Canary",
                "B12345674", new BigDecimal("27.30"), new BigDecimal("157.26"),
                null, "A".repeat(64), "B".repeat(64), Map.of("numero", "FV-001-26-000001"),
                "VERIFACTU-1", "AEAT-SHA256-1", "TPV-ERP-0.0.1");
    }

    private static Map<String, String> address(String line1) {
        return Map.of(
                "linea1", line1,
                "codigoPostal", "35001",
                "ciudad", "Las Palmas",
                "provincia", "Las Palmas",
                "pais", "ES");
    }
}
