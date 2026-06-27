package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class DocumentNumberingTest {

    @ParameterizedTest
    @CsvSource({
        "ALBARAN_VENTA,  AV-001-26-000001",
        "ALBARAN_COMPRA, AC-001-26-000001",
        "FACTURA_VENTA,  FV-001-26-000001",
        "FACTURA_COMPRA, FC-001-26-000001",
        "RECTIFICATIVA_VENTA,  FRV-001-26-000001",
        "RECTIFICATIVA_COMPRA, FRC-001-26-000001"
    })
    void formatsAnnualNumbers(CommercialDocumentType type, String expected) {
        assertThat(DocumentNumbering.format(type, LocalDate.of(2026, 6, 8), 1, "001"))
                .isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({
        "1,     001-260608-00001",
        "99999, 001-260608-99999"
    })
    void formatsDailyTicketNumbers(int sequence, String expected) {
        assertThat(DocumentNumbering.format(
                        CommercialDocumentType.TICKET, LocalDate.of(2026, 6, 8), sequence, "001"))
                .isEqualTo(expected);
    }
}
