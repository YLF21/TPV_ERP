package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DocumentRulesTest {

    @Test
    void lineRequiresProductAndNonZeroQuantity() {
        var document = document();

        assertThatThrownBy(() -> line(document, null, 1))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> line(document, UUID.randomUUID(), 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cantidad");
    }

    @Test
    void lineRoundsAmountsAndAppliesDiscount() {
        var line = new DocumentLine(
                document(), UUID.randomUUID(), 1, 3, "P-1", "Producto", "VENTA",
                new BigDecimal("1.005"), new BigDecimal("10"), false, "IVA",
                new BigDecimal("21"));

        assertThat(line.getPrecioUnitario()).isEqualByComparingTo("1.01");
        assertThat(line.getBase()).isEqualByComparingTo("2.73");
        assertThat(line.getImpuesto()).isEqualByComparingTo("0.57");
        assertThat(line.getTotal()).isEqualByComparingTo("3.30");
    }

    @Test
    void paymentRejectsNegativeAmountsAndInvalidCashChange() {
        var document = document();
        var method = paymentMethod();

        assertThatThrownBy(() -> new DocumentPayment(
                document, method, 1, new BigDecimal("-0.01"), true, null, null, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DocumentPayment(
                document, method, 1, new BigDecimal("10"), true,
                new BigDecimal("9.99"), null, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DocumentPayment(
                document, method, 1, new BigDecimal("10"), true,
                new BigDecimal("20"), BigDecimal.ZERO, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cambio");
    }

    @Test
    void documentAllowsOnlyOnePrincipalPayment() {
        var document = document();
        var method = paymentMethod();

        document.addPayment(new DocumentPayment(
                document, method, 1, new BigDecimal("4"), true, null, null, Instant.now()));

        assertThatThrownBy(() -> document.addPayment(new DocumentPayment(
                document, method, 2, new BigDecimal("6"), true, null, null, Instant.now())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("principal");
    }

    private DocumentLine line(CommercialDocument document, UUID productId, int quantity) {
        return new DocumentLine(
                document, productId, 1, quantity, "P-1", "Producto", "VENTA",
                BigDecimal.ONE, BigDecimal.ZERO, true, "IVA", new BigDecimal("21"));
    }

    private CommercialDocument document() {
        return new CommercialDocument(
                UUID.randomUUID(), UUID.randomUUID(), CommercialDocumentType.TICKET,
                java.time.LocalDate.of(2026, 6, 8), UUID.randomUUID(), BigDecimal.ZERO);
    }

    private PaymentMethod paymentMethod() {
        return new PaymentMethod(UUID.randomUUID(), "EFECTIVO", false);
    }
}
