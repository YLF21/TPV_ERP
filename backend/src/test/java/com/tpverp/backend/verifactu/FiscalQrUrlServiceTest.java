package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FiscalQrUrlServiceTest {

    private final FiscalQrUrlService service = new FiscalQrUrlService();

    @Test
    void buildsProductionVerifactuQrUrlWithRequiredParameters() {
        var record = record("B12345674", "FV-001-26-000001",
                LocalDate.of(2026, 6, 2), new BigDecimal("157.26"));

        assertThat(service.productionUrl(record)).isEqualTo(
                "https://www2.agenciatributaria.gob.es/wlpl/TIKE-CONT/ValidarQR"
                        + "?nif=B12345674&numserie=FV-001-26-000001"
                        + "&fecha=02-06-2026&importe=157.26");
    }

    @Test
    void encodesSpecialCharactersInInvoiceNumber() {
        var record = record("B12345674", "12345678&G33",
                LocalDate.of(2026, 6, 2), new BigDecimal("157.20"));

        assertThat(service.productionUrl(record))
                .contains("numserie=12345678%26G33")
                .endsWith("&importe=157.20");
    }

    private FiscalRecord record(
            String issuerTaxId,
            String number,
            LocalDate issueDate,
            BigDecimal totalAmount) {
        return new FiscalRecord(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), 1, FiscalRecordOperation.ALTA, FiscalDocumentType.F1,
                number, issueDate, Instant.parse("2026-06-02T10:00:00Z"),
                "Atlantic/Canary", issuerTaxId, new BigDecimal("27.30"), totalAmount,
                null, "A".repeat(64), "B".repeat(64), Map.of("numero", number),
                "VERIFACTU-1", "AEAT-SHA256-1", "TPV-ERP-0.0.1");
    }
}
