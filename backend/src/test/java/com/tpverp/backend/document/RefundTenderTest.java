package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RefundTenderTest {

    @Test
    void allowsManualCardRefundWithoutExternalReferenceWhenLinkedToOriginalPayment() {
        var originalPaymentId = UUID.randomUUID();

        var tender = new RefundTender(
                mock(CommercialDocument.class),
                RefundTenderType.CARD,
                new BigDecimal("10.00"),
                originalPaymentId,
                null,
                null,
                Instant.parse("2026-08-04T12:00:00Z"));

        assertThat(tender.getOriginalPaymentId()).isEqualTo(originalPaymentId);
        assertThat(tender.getReference()).isNull();
    }

    @Test
    void stillRejectsUnlinkedManualCardRefundWithoutReference() {
        assertThatThrownBy(() -> new RefundTender(
                mock(CommercialDocument.class),
                RefundTenderType.CARD,
                new BigDecimal("10.00"),
                null,
                null,
                null,
                Instant.parse("2026-08-04T12:00:00Z")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("La devolucion manual con tarjeta requiere referencia");
    }

    @Test
    void allowsUnlinkedManualCardRefundWithExternalReference() {
        var tender = new RefundTender(
                mock(CommercialDocument.class),
                RefundTenderType.CARD,
                new BigDecimal("10.00"),
                null,
                null,
                "MANUAL-REF-42",
                Instant.parse("2026-08-04T12:00:00Z"));

        assertThat(tender.getOriginalPaymentId()).isNull();
        assertThat(tender.getTerminalOperationId()).isNull();
        assertThat(tender.getReference()).isEqualTo("MANUAL-REF-42");
    }

    @Test
    void allowsTransferRefundLinkedToItsOriginalPayment() {
        var originalPaymentId = UUID.randomUUID();

        var tender = new RefundTender(
                mock(CommercialDocument.class),
                RefundTenderType.TRANSFER,
                new BigDecimal("10.00"),
                originalPaymentId,
                null,
                null,
                Instant.parse("2026-08-04T12:00:00Z"));

        assertThat(tender.getOriginalPaymentId()).isEqualTo(originalPaymentId);
        assertThat(tender.getType()).isEqualTo(RefundTenderType.TRANSFER);
    }

    @Test
    void rejectsUntraceableTransferRefund() {
        assertThatThrownBy(() -> new RefundTender(
                mock(CommercialDocument.class),
                RefundTenderType.TRANSFER,
                new BigDecimal("10.00"),
                null,
                null,
                null,
                Instant.parse("2026-08-04T12:00:00Z")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("La devolucion por transferencia requiere el pago original o una referencia");
    }
}
