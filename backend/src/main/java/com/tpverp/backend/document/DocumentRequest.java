package com.tpverp.backend.document;

import com.tpverp.backend.security.sales.OperationAuthorizationRequest;
import com.tpverp.backend.security.sales.SaleOperationCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
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
        @NotEmpty List<@Valid LineRequest> lineas,
        @Size(max = 500) String comentarioInterno,
        @Size(max = 32)
        @Valid Map<@NotNull SaleOperationCode, @NotNull @Valid OperationAuthorizationRequest>
                operationAuthorizations,
        @Size(max = 500) String creditOverrideReason) {

    public DocumentRequest {
        operationAuthorizations = OperationAuthorizationRequest.immutableCopy(
                operationAuthorizations);
    }

    public DocumentRequest(
            UUID almacenId,
            CommercialDocumentType tipo,
            LocalDate fecha,
            UUID clienteId,
            UUID proveedorId,
            String numeroExterno,
            BigDecimal descuentoGlobal,
            boolean directo,
            List<LineRequest> lineas) {
        this(almacenId, tipo, fecha, clienteId, proveedorId, numeroExterno,
                descuentoGlobal, directo, lineas, null, Map.of(), null);
    }

    public DocumentRequest(
            UUID almacenId,
            CommercialDocumentType tipo,
            LocalDate fecha,
            UUID clienteId,
            UUID proveedorId,
            String numeroExterno,
            BigDecimal descuentoGlobal,
            boolean directo,
            List<LineRequest> lineas,
            String comentarioInterno) {
        this(almacenId, tipo, fecha, clienteId, proveedorId, numeroExterno,
                descuentoGlobal, directo, lineas, comentarioInterno, Map.of(), null);
    }

    public DocumentRequest(
            UUID almacenId,
            CommercialDocumentType tipo,
            LocalDate fecha,
            UUID clienteId,
            UUID proveedorId,
            String numeroExterno,
            BigDecimal descuentoGlobal,
            boolean directo,
            List<LineRequest> lineas,
            String comentarioInterno,
            Map<SaleOperationCode, OperationAuthorizationRequest> operationAuthorizations) {
        this(almacenId, tipo, fecha, clienteId, proveedorId, numeroExterno,
                descuentoGlobal, directo, lineas, comentarioInterno,
                operationAuthorizations, null);
    }

    // Maps the HTTP shape to the stable application command.
    public DocumentCommand toCommand() {
        return new DocumentCommand(
                almacenId, tipo, fecha, clienteId, proveedorId,
                numeroExterno, descuentoGlobal, directo,
                lineas.stream().map(LineRequest::toCommand).toList(),
                comentarioInterno);
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
            UUID promotionalCouponId,
            List<String> serialNumbers,
            boolean temporaryNameOverride,
            boolean temporaryPriceOverride) {

        public LineRequest(
                UUID productoId,
                BigDecimal cantidad,
                String codigo,
                String nombre,
                String tarifa,
                BigDecimal precioUnitario,
                BigDecimal descuento,
                boolean impuestosIncluidos,
                String regimenImpuesto,
                BigDecimal porcentajeImpuesto,
                DocumentLineType lineType,
                UUID promotionId,
                UUID promotionVersionId,
                UUID promotionalCouponId,
                List<String> serialNumbers) {
            this(productoId, cantidad, codigo, nombre, tarifa, precioUnitario, descuento,
                    impuestosIncluidos, regimenImpuesto, porcentajeImpuesto, lineType,
                    promotionId, promotionVersionId, promotionalCouponId, serialNumbers,
                    false, false);
        }

        public LineRequest(
                UUID productoId,
                BigDecimal cantidad,
                String codigo,
                String nombre,
                String tarifa,
                BigDecimal precioUnitario,
                BigDecimal descuento,
                boolean impuestosIncluidos,
                String regimenImpuesto,
                BigDecimal porcentajeImpuesto,
                DocumentLineType lineType,
                UUID promotionId,
                UUID promotionVersionId,
                UUID promotionalCouponId) {
            this(productoId, cantidad, codigo, nombre, tarifa, precioUnitario, descuento,
                    impuestosIncluidos, regimenImpuesto, porcentajeImpuesto, lineType,
                    promotionId, promotionVersionId, promotionalCouponId, List.of(),
                    false, false);
        }

        DocumentLineCommand toCommand() {
            var resolvedType = lineType == null ? DocumentLineType.PRODUCT : lineType;
            var command = new DocumentLineCommand(
                    productoId, cantidad, codigo, nombre, tarifa, precioUnitario,
                    descuento, impuestosIncluidos, regimenImpuesto, porcentajeImpuesto,
                    resolvedType, promotionId, promotionVersionId, promotionalCouponId,
                    serialNumbers, temporaryNameOverride, temporaryPriceOverride);
            command.requireClientProductLine();
            return command;
        }
    }
}
