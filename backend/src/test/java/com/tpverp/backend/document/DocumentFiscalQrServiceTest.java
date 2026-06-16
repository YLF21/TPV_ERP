package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.tpverp.backend.verifactu.FiscalDocumentType;
import com.tpverp.backend.verifactu.FiscalQrUrlService;
import com.tpverp.backend.verifactu.FiscalRecord;
import com.tpverp.backend.verifactu.FiscalRecordOperation;
import com.tpverp.backend.verifactu.FiscalRecordRepository;
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
