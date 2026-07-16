package com.tpverp.backend.document;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

public record DocumentReportView(
        UUID id,
        CommercialDocumentType tipo,
        DocumentStatus estado,
        String numero,
        String numeroExterno,
        LocalDate fecha,
        LocalDate fechaVencimiento,
        BigDecimal base,
        BigDecimal impuesto,
        BigDecimal total,
        BigDecimal pendiente,
        BigDecimal descuentoGlobal,
        String numTicket,
        boolean origenStock,
        UUID clienteId,
        String clienteCodigo,
        String clienteNombre,
        UUID proveedorId,
        String proveedorCodigo,
        String proveedorNombre,
        UUID almacenId,
        String almacenNombre,
        int lineas,
        List<DocumentView.PaymentView> payments) {

    static DocumentReportView from(
            CommercialDocument document,
            PartySummary customer,
            PartySummary supplier,
            String warehouseName) {
        return new DocumentReportView(
                document.getId(),
                document.getTipo(),
                document.getEstado(),
                document.getNumero(),
                document.getNumeroExterno(),
                document.getFecha(),
                document.getFechaVencimiento(),
                document.getBaseTotal(),
                document.getImpuestoTotal(),
                document.getTotal(),
                document.getPendingTotal(),
                document.getDescuentoGlobal(),
                document.getNumTicket(),
                document.isOrigenStock(),
                document.getClienteId(),
                customer == null ? "" : customer.code(),
                customer == null ? "" : customer.name(),
                document.getProveedorId(),
                supplier == null ? "" : supplier.code(),
                supplier == null ? "" : supplier.name(),
                document.getAlmacenId(),
                warehouseName == null ? "" : warehouseName,
                document.getLineas().size(),
                document.getPagos().stream()
                        .sorted(Comparator.comparingInt(DocumentPayment::getPosicion))
                        .map(DocumentView.PaymentView::from)
                        .toList());
    }

    record PartySummary(String code, String name) {
    }
}
