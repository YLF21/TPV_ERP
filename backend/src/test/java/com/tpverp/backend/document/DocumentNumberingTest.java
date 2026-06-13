package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class DocumentNumberingTest {

    @ParameterizedTest
    @CsvSource({
        "ALBARAN_VENTA,  AV-2026-000001",
        "ALBARAN_COMPRA, AC-2026-000001",
        "FACTURA_VENTA,  FV-2026-000001",
        "FACTURA_COMPRA, FC-2026-000001",
        "RECTIFICATIVA_VENTA,  FRV-2026-000001",
        "RECTIFICATIVA_COMPRA, FRC-2026-000001"
    })
    void formatsAnnualNumbers(TipoDocumento type, String expected) {
        assertThat(DocumentNumbering.format(type, LocalDate.of(2026, 6, 8), 1))
                .isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({
        "1,     26060800001",
        "99999, 26060899999"
    })
    void formatsDailyTicketNumbers(int sequence, String expected) {
        assertThat(DocumentNumbering.format(TipoDocumento.TICKET, LocalDate.of(2026, 6, 8), sequence))
                .isEqualTo(expected);
    }
}
