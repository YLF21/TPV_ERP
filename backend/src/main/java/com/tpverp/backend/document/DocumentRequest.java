package com.tpverp.backend.document;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record DocumentRequest(
        @NotNull UUID almacenId,
        @NotNull CommercialDocumentType tipo,
        @NotNull LocalDate fecha,
        UUID clienteId,
        UUID proveedorId,
        String numeroExterno,
        @NotNull BigDecimal descuentoGlobal,
        boolean directo,
        @NotEmpty List<@Valid LineRequest> lineas) {

    // Traduce la forma HTTP al comando estable de aplicación.
    public DocumentCommand toCommand() {
        return new DocumentCommand(
                almacenId, tipo, fecha, clienteId, proveedorId,
                numeroExterno, descuentoGlobal, directo,
                lineas.stream().map(LineRequest::toCommand).toList());
    }

    public record LineRequest(
            @NotNull UUID productoId,
            int cantidad,
            @NotNull String codigo,
            @NotNull String nombre,
            String tarifa,
            @NotNull BigDecimal precioUnitario,
            @NotNull BigDecimal descuento,
            boolean impuestosIncluidos,
            @NotNull String regimenImpuesto,
            @NotNull BigDecimal porcentajeImpuesto) {

        DocumentLineCommand toCommand() {
            return new DocumentLineCommand(
                    productoId, cantidad, codigo, nombre, tarifa, precioUnitario,
                    descuento, impuestosIncluidos, regimenImpuesto, porcentajeImpuesto);
        }
    }
}
