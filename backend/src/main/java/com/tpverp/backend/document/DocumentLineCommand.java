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
        BigDecimal porcentajeImpuesto,
        DocumentLineType lineType,
        UUID promotionId,
        UUID promotionVersionId,
        UUID promotionalCouponId) {

    public DocumentLineCommand(
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
        this(productoId, cantidad, codigo, nombre, tarifa, precioUnitario,
                descuento, impuestosIncluidos, regimenImpuesto, porcentajeImpuesto,
                DocumentLineType.PRODUCT, null, null, null);
    }

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
                impuestosIncluidos, regimenImpuesto, porcentajeImpuesto, lineType,
                promotionId, promotionVersionId, promotionalCouponId);
    }

    public DocumentLineCommand withDiscount(BigDecimal discount, String rate) {
        return new DocumentLineCommand(
                productoId, cantidad, codigo, nombre, rate, precioUnitario, discount,
                impuestosIncluidos, regimenImpuesto, porcentajeImpuesto, lineType,
                promotionId, promotionVersionId, promotionalCouponId);
    }

    static DocumentLineCommand from(DocumentLine line) {
        return new DocumentLineCommand(
                line.getProductoId(), line.getCantidad(), line.getCodigo(),
                line.getNombre(), line.getTarifa(), line.getPrecioUnitario(),
                line.getDescuento(), line.isImpuestosIncluidos(),
                line.getRegimenImpuesto(), line.getPorcentajeImpuesto(),
                line.getLineType(), line.getPromotionId(), line.getPromotionVersionId(),
                line.getPromotionalCouponId());
    }

    // Converts validated input into a line with a fiscal snapshot.
    public DocumentLine toEntity(CommercialDocument document, int position) {
        if (lineType != null && lineType != DocumentLineType.PRODUCT) {
            return DocumentLine.special(
                    document, position, nombre, precioUnitario, impuestosIncluidos,
                    regimenImpuesto, porcentajeImpuesto, promotionId,
                    promotionVersionId, promotionalCouponId);
        }
        return new DocumentLine(
                document, productoId, position, cantidad, codigo, nombre, tarifa,
                precioUnitario, descuento, impuestosIncluidos, regimenImpuesto,
                porcentajeImpuesto);
    }

    DocumentLine toEntity(CommercialDocument document) {
        return toEntity(document, document.getLineas().size() + 1);
    }
}
