package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;

import com.tpverp.backend.verifactu.FiscalDocumentType;
import com.tpverp.backend.verifactu.FiscalQrUrlService;
import com.tpverp.backend.verifactu.FiscalRecord;
import com.tpverp.backend.verifactu.FiscalRecordOperation;
import com.tpverp.backend.verifactu.FiscalRecordRepository;
import com.tpverp.backend.verifactu.FiscalPrintSnapshotRecord;
import com.tpverp.backend.verifactu.FiscalPrintSnapshotRecordRepository;
import com.tpverp.backend.verifactu.FiscalRuntimeProperties;
import java.math.BigDecimal;
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
        when(artifacts.findByRecordId(record.getId())).thenReturn(Optional.empty());

        var service = new DocumentFiscalQrService(records, new FiscalQrUrlService(), artifacts,
                mock(FiscalRuntimeProperties.class), snapshots);

        assertThat(service.qrUrl(documentId)).isNull();
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
}
