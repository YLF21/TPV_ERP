package com.tpverp.backend.document;

import java.math.BigDecimal;
import java.util.UUID;

public record DocumentLineCommand(
        UUID productoId,
        BigDecimal cantidad,
        String codigo,
        String nombre,
        String tarifa,
        BigDecimal precioUnitario,
        BigDecimal descuento,
        boolean impuestosIncluidos,
        String regimenImpuesto,
        BigDecimal porcentajeImpuesto) {

    public DocumentLineCommand(
            UUID productoId,
            int cantidad,
            String codigo,
            String nombre,
            String tarifa,
            BigDecimal precioUnitario,
            BigDecimal descuento,
            boolean impuestosIncluidos,
            String regimenImpuesto,
            BigDecimal porcentajeImpuesto) {
        this(productoId, BigDecimal.valueOf(cantidad), codigo, nombre, tarifa,
                precioUnitario, descuento, impuestosIncluidos, regimenImpuesto, porcentajeImpuesto);
    }

    // Converts validated input into a line with a fiscal snapshot.
    public DocumentLine toEntity(CommercialDocument document, int position) {
        return new DocumentLine(
                document, productoId, position, cantidad, codigo, nombre, tarifa,
                precioUnitario, descuento, impuestosIncluidos, regimenImpuesto,
                porcentajeImpuesto);
    }

    DocumentLine toEntity(CommercialDocument document) {
        return toEntity(document, document.getLineas().size() + 1);
    }
}
