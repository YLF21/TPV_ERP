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

    public DocumentLineCommand withPrice(BigDecimal price, String rate) {
        return new DocumentLineCommand(
                productoId, cantidad, codigo, nombre, rate, price, descuento,
                impuestosIncluidos, regimenImpuesto, porcentajeImpuesto);
    }

    public DocumentLineCommand withDiscount(BigDecimal discount, String rate) {
        return new DocumentLineCommand(
                productoId, cantidad, codigo, nombre, rate, precioUnitario, discount,
                impuestosIncluidos, regimenImpuesto, porcentajeImpuesto);
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
