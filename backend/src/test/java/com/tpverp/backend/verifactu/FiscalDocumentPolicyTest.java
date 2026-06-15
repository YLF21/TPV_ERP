package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tpverp.backend.document.Documento;
import com.tpverp.backend.document.EstadoDocumento;
import com.tpverp.backend.document.TipoDocumento;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class FiscalDocumentPolicyTest {

    private final FiscalDocumentPolicy policy = new FiscalDocumentPolicy();

    @Test
    void rechazaBorradoresYCompras() {
        assertThatThrownBy(() -> policy.validate(
                document(TipoDocumento.TICKET, EstadoDocumento.BORRADOR, BigDecimal.TEN),
                FiscalRecordOperation.ALTA, FiscalDocumentType.F2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("estado");
        assertThatThrownBy(() -> policy.validate(
                document(TipoDocumento.FACTURA_COMPRA, EstadoDocumento.PENDIENTE, BigDecimal.TEN),
                FiscalRecordOperation.ALTA, FiscalDocumentType.F1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("venta");
    }

    @Test
    void exigeF2ParaTicketPositivoYR5ParaTicketNegativo() {
        policy.validate(
                document(TipoDocumento.TICKET, EstadoDocumento.CONFIRMADO, BigDecimal.ZERO),
                FiscalRecordOperation.ALTA, FiscalDocumentType.F2);
        policy.validate(
                document(TipoDocumento.TICKET, EstadoDocumento.CONFIRMADO, BigDecimal.ONE.negate()),
                FiscalRecordOperation.ALTA, FiscalDocumentType.R5);

        assertThatThrownBy(() -> policy.validate(
                document(TipoDocumento.TICKET, EstadoDocumento.CONFIRMADO, BigDecimal.TEN),
                FiscalRecordOperation.ALTA, FiscalDocumentType.F1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("F2");
    }

    @Test
    void validaFacturasYRectificativasDeVenta() {
        policy.validate(
                document(TipoDocumento.FACTURA_VENTA, EstadoDocumento.PENDIENTE, BigDecimal.TEN),
                FiscalRecordOperation.ALTA, FiscalDocumentType.F1);
        policy.validate(
                document(TipoDocumento.FACTURA_VENTA, EstadoDocumento.PAGADO, BigDecimal.TEN),
                FiscalRecordOperation.ALTA, FiscalDocumentType.F3);
        policy.validate(
                document(TipoDocumento.RECTIFICATIVA_VENTA, EstadoDocumento.PENDIENTE, BigDecimal.TEN),
                FiscalRecordOperation.ALTA, FiscalDocumentType.R1);

        assertThatThrownBy(() -> policy.validate(
                document(TipoDocumento.FACTURA_VENTA, EstadoDocumento.ANULADO, BigDecimal.TEN),
                FiscalRecordOperation.ALTA, FiscalDocumentType.F1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("estado");
    }

    @Test
    void anulacionSoloAdmiteTicketsAnulados() {
        policy.validate(
                document(TipoDocumento.TICKET, EstadoDocumento.ANULADO, BigDecimal.TEN),
                FiscalRecordOperation.ANULACION, FiscalDocumentType.F2);

        assertThatThrownBy(() -> policy.validate(
                document(TipoDocumento.FACTURA_VENTA, EstadoDocumento.ANULADO, BigDecimal.TEN),
                FiscalRecordOperation.ANULACION, FiscalDocumentType.F1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ticket");
    }

    private static Documento document(
            TipoDocumento type, EstadoDocumento state, BigDecimal total) {
        var document = mock(Documento.class);
        when(document.getTipo()).thenReturn(type);
        when(document.getEstado()).thenReturn(state);
        when(document.getTotal()).thenReturn(total);
        return document;
    }
}
