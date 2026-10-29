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
        boolean wholesaleMode,
        UUID customerAddressId,
        @com.fasterxml.jackson.annotation.JsonProperty(access = com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
        DocumentCustomerContact customerContactSnapshot) {

    public DocumentCommand(UUID almacenId, CommercialDocumentType tipo, LocalDate fecha, UUID clienteId,
            UUID proveedorId, String numeroExterno, BigDecimal descuentoGlobal, boolean directo,
            List<DocumentLineCommand> lineas, String comentarioInterno, BigDecimal documentDiscountPercent,
            boolean wholesaleMode, UUID customerAddressId) {
        this(almacenId, tipo, fecha, clienteId, proveedorId, numeroExterno, descuentoGlobal, directo,
                lineas, comentarioInterno, documentDiscountPercent, wholesaleMode, customerAddressId, null);
    }

    DocumentCommand withFrozenCustomerContact(DocumentCustomerContact contact) {
        return contact == null ? this : new DocumentCommand(almacenId, tipo, fecha, clienteId, proveedorId,
                numeroExterno, descuentoGlobal, directo, lineas, comentarioInterno, documentDiscountPercent,
                wholesaleMode, contact.id(), contact);
    }


    DocumentCommand withoutCustomerAddressId() {
        return new DocumentCommand(almacenId, tipo, fecha, clienteId, proveedorId, numeroExterno,
                descuentoGlobal, directo, lineas, comentarioInterno, documentDiscountPercent, wholesaleMode, null);
    }

    public DocumentCommand(UUID almacenId, CommercialDocumentType tipo, LocalDate fecha,
            UUID clienteId, UUID proveedorId, String numeroExterno, BigDecimal descuentoGlobal,
            boolean directo, List<DocumentLineCommand> lineas, String comentarioInterno,
            BigDecimal documentDiscountPercent, boolean wholesaleMode) {
        this(almacenId, tipo, fecha, clienteId, proveedorId, numeroExterno, descuentoGlobal,
                directo, lineas, comentarioInterno, documentDiscountPercent, wholesaleMode, null);
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
