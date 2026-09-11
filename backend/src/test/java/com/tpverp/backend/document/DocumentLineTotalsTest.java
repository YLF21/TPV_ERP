package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DocumentLineTotalsTest {

    @Test
    void extendsThreeDecimalUnitPriceBeforeRoundingAndKeepsTaxBalance() {
        var document = new CommercialDocument(UUID.randomUUID(), UUID.randomUUID(), CommercialDocumentType.TICKET,
                LocalDate.of(2026, 9, 8), UUID.randomUUID(), BigDecimal.ZERO);
        var line = new DocumentLine(document, UUID.randomUUID(), 1, BigDecimal.TEN, "P-1", "Producto",
                null, new BigDecimal("2.208"), BigDecimal.ZERO, true, "IVA", new BigDecimal("21.00"));
        document.addLine(line);
        assertThat(line.getPrecioUnitario()).isEqualByComparingTo("2.208");
        assertThat(line.getTotal()).isEqualByComparingTo("22.08");
        assertThat(line.getBase().add(line.getImpuesto())).isEqualByComparingTo(line.getTotal());
    }

    @Test
    void aggregatesMemberBalanceGrossAndTaxableBaseSeparately() {
        var document = new CommercialDocument(
                UUID.randomUUID(), UUID.randomUUID(), CommercialDocumentType.TICKET,
                LocalDate.of(2026, 8, 19), UUID.randomUUID(), BigDecimal.ZERO);
        document.addLine(new DocumentLine(
                document, UUID.randomUUID(), 1, BigDecimal.ONE, "P-1", "Producto",
                null, new BigDecimal("20.00"), BigDecimal.ZERO, true,
                "IVA", new BigDecimal("21.00")));
        document.addLine(DocumentLine.special(
                document, 2, "SALDO DE MIEMBRO", new BigDecimal("-6.00"), true,
                "IVA", new BigDecimal("21.00"), null, null, null,
                DocumentLineType.MEMBER_BALANCE));

        assertThat(DocumentLineTotals.memberBalanceTotal(document.getLineas()))
                .isEqualByComparingTo("6.00");
        assertThat(DocumentLineTotals.memberBalanceBaseTotal(document.getLineas()))
                .isEqualByComparingTo("4.96");
    }
}
