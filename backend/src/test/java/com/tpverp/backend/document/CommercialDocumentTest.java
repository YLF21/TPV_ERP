package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CommercialDocumentTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final Instant NOW = Instant.parse("2026-07-16T10:15:30Z");

    @Test
    void receivableStateUsesOnlyRealPayments() {
        var document = saleInvoice(new BigDecimal("100.00"));

        document.confirm("FV-1", USER_ID, NOW, false);
        document.setDueDate(LocalDate.of(2026, 8, 15));

        assertThat(document.getPendingTotal()).isEqualByComparingTo("100.00");
        assertThat(document.getEstado()).isEqualTo(DocumentStatus.PENDIENTE);
        assertThat(document.getDueDate()).isEqualTo(LocalDate.of(2026, 8, 15));
    }

    @Test
    void paymentCanCarryAnIdempotencyRequestId() {
        var document = saleInvoice(new BigDecimal("100.00"));
        var method = new PaymentMethod(UUID.randomUUID(), "TRANSFERENCIA", true);
        var requestId = UUID.randomUUID();

        var payment = new DocumentPayment(
                document, method, 1, new BigDecimal("25.00"), true,
                null, null, null, "bank-ref", NOW,
                null, null, null, null, null, requestId);

        assertThat(payment.getRequestId()).isEqualTo(requestId);
    }

    @Test
    void documentsOutsideCustomerSalesDoNotBecomeReceivables() {
        var document = documentWithTotal(CommercialDocumentType.FACTURA_COMPRA, new BigDecimal("100.00"));

        document.confirm("FC-1", USER_ID, NOW, false);

        assertThat(document.getEstado()).isEqualTo(DocumentStatus.CONFIRMADO);
    }

    private static CommercialDocument saleInvoice(BigDecimal total) {
        return documentWithTotal(CommercialDocumentType.FACTURA_VENTA, total);
    }

    private static CommercialDocument documentWithTotal(
            CommercialDocumentType type, BigDecimal total) {
        var document = new CommercialDocument(
                UUID.randomUUID(), UUID.randomUUID(), type, LocalDate.of(2026, 7, 16),
                USER_ID, BigDecimal.ZERO);
        document.addLine(new DocumentLine(
                document, UUID.randomUUID(), 1, BigDecimal.ONE, "P-1", "Product", null,
                total, BigDecimal.ZERO, true, "IVA", BigDecimal.ZERO));
        return document;
    }
}
