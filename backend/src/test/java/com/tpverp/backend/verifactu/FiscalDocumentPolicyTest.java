package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tpverp.backend.document.CommercialDocument;
import com.tpverp.backend.document.DocumentStatus;
import com.tpverp.backend.document.CommercialDocumentType;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class FiscalDocumentPolicyTest {

    private final FiscalDocumentPolicy policy = new FiscalDocumentPolicy();

    @Test
    void rechazaBorradoresYCompras() {
        assertThatThrownBy(() -> policy.validate(
                document(CommercialDocumentType.TICKET, DocumentStatus.BORRADOR, BigDecimal.TEN),
                FiscalRecordOperation.ALTA, FiscalDocumentType.F2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("estado");
        assertThatThrownBy(() -> policy.validate(
                document(CommercialDocumentType.FACTURA_COMPRA, DocumentStatus.PENDIENTE, BigDecimal.TEN),
                FiscalRecordOperation.ALTA, FiscalDocumentType.F1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("venta");
    }

    @Test
    void exigeF2ParaTicketPositivoYR5ParaTicketNegativo() {
        policy.validate(
                document(CommercialDocumentType.TICKET, DocumentStatus.CONFIRMADO, BigDecimal.ZERO),
                FiscalRecordOperation.ALTA, FiscalDocumentType.F2);
        policy.validate(
                document(CommercialDocumentType.TICKET, DocumentStatus.CONFIRMADO, BigDecimal.ONE.negate()),
                FiscalRecordOperation.ALTA, FiscalDocumentType.R5);

        assertThatThrownBy(() -> policy.validate(
                document(CommercialDocumentType.TICKET, DocumentStatus.CONFIRMADO, BigDecimal.TEN),
                FiscalRecordOperation.ALTA, FiscalDocumentType.F1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("F2");
    }

    @Test
    void validaFacturasYRectificativasDeVenta() {
        policy.validate(
                document(CommercialDocumentType.FACTURA_VENTA, DocumentStatus.PENDIENTE, BigDecimal.TEN),
                FiscalRecordOperation.ALTA, FiscalDocumentType.F1);
        policy.validate(
                document(CommercialDocumentType.FACTURA_VENTA, DocumentStatus.PAGADO, BigDecimal.TEN),
                FiscalRecordOperation.ALTA, FiscalDocumentType.F3);
        policy.validate(
                document(CommercialDocumentType.RECTIFICATIVA_VENTA, DocumentStatus.PENDIENTE, BigDecimal.TEN),
                FiscalRecordOperation.ALTA, FiscalDocumentType.R1);

        assertThatThrownBy(() -> policy.validate(
                document(CommercialDocumentType.FACTURA_VENTA, DocumentStatus.ANULADO, BigDecimal.TEN),
                FiscalRecordOperation.ALTA, FiscalDocumentType.F1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("estado");
    }

    @Test
    void anulacionSoloAdmiteTicketsAnulados() {
        policy.validate(
                document(CommercialDocumentType.TICKET, DocumentStatus.ANULADO, BigDecimal.TEN),
                FiscalRecordOperation.ANULACION, FiscalDocumentType.F2);

        assertThatThrownBy(() -> policy.validate(
                document(CommercialDocumentType.FACTURA_VENTA, DocumentStatus.ANULADO, BigDecimal.TEN),
                FiscalRecordOperation.ANULACION, FiscalDocumentType.F1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ticket");
    }

    private static CommercialDocument document(
            CommercialDocumentType type, DocumentStatus state, BigDecimal total) {
        var document = mock(CommercialDocument.class);
        when(document.getTipo()).thenReturn(type);
        when(document.getEstado()).thenReturn(state);
        when(document.getTotal()).thenReturn(total);
        return document;
    }
}
