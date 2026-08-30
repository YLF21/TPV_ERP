package com.tpverp.backend.document;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record DocumentCommand(
        UUID almacenId,
        CommercialDocumentType tipo,
        LocalDate fecha,
        UUID clienteId,
        UUID proveedorId,
        String numeroExterno,
        BigDecimal descuentoGlobal,
        boolean directo,
        List<DocumentLineCommand> lineas,
        String comentarioInterno,
        BigDecimal documentDiscountPercent,
        boolean wholesaleMode) {

    public DocumentCommand(
            UUID almacenId,
            CommercialDocumentType tipo,
            LocalDate fecha,
            UUID clienteId,
            UUID proveedorId,
            String numeroExterno,
            BigDecimal descuentoGlobal,
            boolean directo,
            List<DocumentLineCommand> lineas,
            String comentarioInterno) {
        this(almacenId, tipo, fecha, clienteId, proveedorId, numeroExterno,
                descuentoGlobal, directo, lineas, comentarioInterno, null);
    }

    public DocumentCommand(
            UUID almacenId,
            CommercialDocumentType tipo,
            LocalDate fecha,
            UUID clienteId,
            UUID proveedorId,
            String numeroExterno,
            BigDecimal descuentoGlobal,
            boolean directo,
            List<DocumentLineCommand> lineas) {
        this(almacenId, tipo, fecha, clienteId, proveedorId, numeroExterno,
                descuentoGlobal, directo, lineas, null);
    }

    public DocumentCommand(
            UUID almacenId, CommercialDocumentType tipo, LocalDate fecha, UUID clienteId,
            UUID proveedorId, String numeroExterno, BigDecimal descuentoGlobal, boolean directo,
            List<DocumentLineCommand> lineas, String comentarioInterno,
            BigDecimal documentDiscountPercent) {
        this(almacenId, tipo, fecha, clienteId, proveedorId, numeroExterno, descuentoGlobal,
                directo, lineas, comentarioInterno, documentDiscountPercent, false);
    }
}
