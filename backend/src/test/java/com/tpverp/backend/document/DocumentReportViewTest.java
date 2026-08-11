package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class DocumentReportViewTest {

    @Test
    void reportsNoPendingAmountAfterACompleteRectificationRefund() {
        var rectification = rectification("-1000000.00");

        assertThat(DocumentReportView.pendingAmount(
                rectification, new BigDecimal("1000000.00"))).isZero();
    }

    @Test
    void reportsTheNegativeRemainderAfterAPartialRectificationRefund() {
        var rectification = rectification("-100.00");

        assertThat(DocumentReportView.pendingAmount(
                rectification, new BigDecimal("40.00")))
                .isEqualByComparingTo("-60.00");
    }

    @Test
    void reportsTheFullNegativeAmountBeforeARectificationRefund() {
        var rectification = rectification("-100.00");

        assertThat(DocumentReportView.pendingAmount(rectification, BigDecimal.ZERO))
                .isEqualByComparingTo("-100.00");
    }

    @Test
    void preservesTheExistingPendingCalculationForOrdinaryInvoices() {
        var invoice = mock(CommercialDocument.class);
        when(invoice.getTipo()).thenReturn(CommercialDocumentType.FACTURA_VENTA);
        when(invoice.getTotal()).thenReturn(new BigDecimal("100.00"));
        when(invoice.getPendingTotal()).thenReturn(new BigDecimal("30.00"));

        assertThat(DocumentReportView.pendingAmount(invoice, new BigDecimal("100.00")))
                .isEqualByComparingTo("30.00");
    }

    private static CommercialDocument rectification(String total) {
        var document = mock(CommercialDocument.class);
        var amount = new BigDecimal(total);
        when(document.getTipo()).thenReturn(CommercialDocumentType.RECTIFICATIVA_VENTA);
        when(document.getTotal()).thenReturn(amount);
        when(document.getPendingTotal()).thenReturn(amount);
        return document;
    }
}
