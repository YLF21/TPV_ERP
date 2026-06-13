package com.tpverp.backend.document;

import java.math.BigDecimal;
import java.util.UUID;

public record DocumentLineCommand(
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

    // Convierte la entrada validada en una línea con snapshot fiscal.
    public DocumentoLinea toEntity(Documento document, int position) {
        return new DocumentoLinea(
                document, productoId, position, cantidad, codigo, nombre, tarifa,
                precioUnitario, descuento, impuestosIncluidos, regimenImpuesto,
                porcentajeImpuesto);
    }

    DocumentoLinea toEntity(Documento document) {
        return toEntity(document, document.getLineas().size() + 1);
    }
}
