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

    // Maps the HTTP shape to the stable application command.
    public DocumentCommand toCommand() {
        return new DocumentCommand(
                almacenId, tipo, fecha, clienteId, proveedorId,
                numeroExterno, descuentoGlobal, directo,
                lineas.stream().map(LineRequest::toCommand).toList());
    }

    public record LineRequest(
            UUID productoId,
            @NotNull BigDecimal cantidad,
            @NotNull String codigo,
            @NotNull String nombre,
            String tarifa,
            @NotNull BigDecimal precioUnitario,
            @NotNull BigDecimal descuento,
            boolean impuestosIncluidos,
            @NotNull String regimenImpuesto,
            @NotNull BigDecimal porcentajeImpuesto,
            DocumentLineType lineType,
            UUID promotionId,
            UUID promotionVersionId,
            UUID promotionalCouponId) {

        DocumentLineCommand toCommand() {
            var resolvedType = lineType == null ? DocumentLineType.PRODUCT : lineType;
            if (resolvedType == DocumentLineType.PRODUCT && productoId == null) {
                throw new IllegalArgumentException("productoId es obligatorio");
            }
            return new DocumentLineCommand(
                    productoId, cantidad, codigo, nombre, tarifa, precioUnitario,
                    descuento, impuestosIncluidos, regimenImpuesto, porcentajeImpuesto,
                    resolvedType, promotionId, promotionVersionId, promotionalCouponId);
        }
    }
}
