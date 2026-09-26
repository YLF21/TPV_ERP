package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tpverp.backend.document.CommercialDocument;
import com.tpverp.backend.document.DocumentStatus;
import com.tpverp.backend.document.CommercialDocumentType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class FiscalDocumentPolicyTest {

    private final FiscalDocumentPolicy policy = new FiscalDocumentPolicy();

    @Test
    void rechazaBorradores() {
        assertThatThrownBy(() -> policy.validate(
                document(CommercialDocumentType.TICKET, DocumentStatus.BORRADOR, BigDecimal.TEN),
                FiscalRecordOperation.ALTA, FiscalDocumentType.F2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("estado");
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

    @ParameterizedTest
    @EnumSource(value = DocumentStatus.class, names = {"PENDIENTE", "PARCIAL", "PAGADO"})
    void admiteTicketACreditoConfirmadoConSuEstadoDeCobro(DocumentStatus state) {
        policy.validate(confirmedReceivableTicket(state),
                FiscalRecordOperation.ALTA, FiscalDocumentType.F2);
    }

    @ParameterizedTest
    @EnumSource(value = DocumentStatus.class, names = {"PENDIENTE", "PARCIAL", "PAGADO"})
    void rechazaEstadoDeCobroSinMarcaDeCuentaACobrar(DocumentStatus state) {
        var ticket = confirmedReceivableTicket(state);
        when(ticket.isCuentaCobrar()).thenReturn(false);

        assertInvalidTicketState(ticket);
    }

    @ParameterizedTest
    @EnumSource(value = DocumentStatus.class, names = {"PENDIENTE", "PARCIAL", "PAGADO"})
    void rechazaTicketACreditoSinCliente(DocumentStatus state) {
        var ticket = confirmedReceivableTicket(state);
        when(ticket.getClienteId()).thenReturn(null);

        assertInvalidTicketState(ticket);
    }

    @ParameterizedTest
    @EnumSource(value = DocumentStatus.class, names = {"PENDIENTE", "PARCIAL", "PAGADO"})
    void rechazaTicketACreditoSinConfirmacionCompleta(DocumentStatus state) {
        var withoutNumber = confirmedReceivableTicket(state);
        when(withoutNumber.getNumero()).thenReturn(null);
        assertInvalidTicketState(withoutNumber);

        var blankNumber = confirmedReceivableTicket(state);
        when(blankNumber.getNumero()).thenReturn(" ");
        assertInvalidTicketState(blankNumber);

        var withoutTimestamp = confirmedReceivableTicket(state);
        when(withoutTimestamp.getConfirmadoEn()).thenReturn(null);
        assertInvalidTicketState(withoutTimestamp);

        var withoutUser = confirmedReceivableTicket(state);
        when(withoutUser.getConfirmadoPor()).thenReturn(null);
        assertInvalidTicketState(withoutUser);
    }

    @ParameterizedTest
    @EnumSource(value = DocumentStatus.class, names = {"BORRADOR", "ANULADO"})
    void rechazaAltaDeTicketACreditoBorradorOAnulado(DocumentStatus state) {
        assertInvalidTicketState(confirmedReceivableTicket(state));
    }

    @ParameterizedTest
    @EnumSource(value = DocumentStatus.class, names = {"PENDIENTE", "PARCIAL", "PAGADO"})
    void conservaTipoFiscalDeTicketsACredito(DocumentStatus state) {
        var ticket = confirmedReceivableTicket(state);
        assertThatThrownBy(() -> policy.validate(
                ticket, FiscalRecordOperation.ALTA, FiscalDocumentType.F1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("F2");
        assertThatThrownBy(() -> policy.validate(
                ticket, FiscalRecordOperation.ALTA, FiscalDocumentType.R5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("F2");

        when(ticket.getTotal()).thenReturn(BigDecimal.ONE.negate());
        policy.validate(ticket, FiscalRecordOperation.ALTA, FiscalDocumentType.R5);
        assertThatThrownBy(() -> policy.validate(
                ticket, FiscalRecordOperation.ALTA, FiscalDocumentType.F2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("R5");
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

    private void assertInvalidTicketState(CommercialDocument ticket) {
        assertThatThrownBy(() -> policy.validate(
                ticket, FiscalRecordOperation.ALTA, FiscalDocumentType.F2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("estado");
    }

    private static CommercialDocument confirmedReceivableTicket(DocumentStatus state) {
        var ticket = document(CommercialDocumentType.TICKET, state, new BigDecimal("12.60"));
        when(ticket.isCuentaCobrar()).thenReturn(true);
        when(ticket.getClienteId()).thenReturn(UUID.randomUUID());
        when(ticket.getNumero()).thenReturn("T-1");
        when(ticket.getConfirmadoEn()).thenReturn(Instant.parse("2026-09-26T10:00:00Z"));
        when(ticket.getConfirmadoPor()).thenReturn(UUID.randomUUID());
        return ticket;
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
