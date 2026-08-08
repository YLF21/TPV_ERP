package com.tpverp.backend.document;

import java.math.BigDecimal;
import java.util.List;
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
        UUID promotionalCouponId,
        List<String> serialNumbers,
        boolean temporaryNameOverride,
        boolean temporaryPriceOverride,
        TicketReturnService.ReturnSourceType returnSourceType,
        String returnSourceCode,
        UUID returnSourceTicketId,
        UUID originalDocumentLineId,
        UUID giftReceiptLineId,
        BigDecimal frozenBase,
        BigDecimal frozenTax,
        BigDecimal frozenTotal) {

    private static final String HISTORICAL_OPEN_PRICE_RATE =
            "OPEN_PRICE";

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
            BigDecimal porcentajeImpuesto,
            DocumentLineType lineType,
            UUID promotionId,
            UUID promotionVersionId,
            UUID promotionalCouponId,
            List<String> serialNumbers,
            boolean temporaryNameOverride,
            boolean temporaryPriceOverride,
            TicketReturnService.ReturnSourceType returnSourceType,
            String returnSourceCode,
            UUID returnSourceTicketId,
            UUID originalDocumentLineId,
            UUID giftReceiptLineId) {
        this(productoId, cantidad, codigo, nombre, tarifa, precioUnitario,
                descuento, impuestosIncluidos, regimenImpuesto,
                porcentajeImpuesto, lineType, promotionId, promotionVersionId,
                promotionalCouponId, serialNumbers, temporaryNameOverride,
                temporaryPriceOverride, returnSourceType, returnSourceCode,
                returnSourceTicketId, originalDocumentLineId, giftReceiptLineId,
                null, null, null);
    }

    public DocumentLineCommand(
            UUID productoId, BigDecimal cantidad, String codigo, String nombre,
            String tarifa, BigDecimal precioUnitario, BigDecimal descuento,
            boolean impuestosIncluidos, String regimenImpuesto,
            BigDecimal porcentajeImpuesto, DocumentLineType lineType,
            UUID promotionId, UUID promotionVersionId, UUID promotionalCouponId,
            List<String> serialNumbers, boolean temporaryNameOverride,
            boolean temporaryPriceOverride) {
        this(productoId, cantidad, codigo, nombre, tarifa, precioUnitario, descuento,
                impuestosIncluidos, regimenImpuesto, porcentajeImpuesto, lineType,
                promotionId, promotionVersionId, promotionalCouponId, serialNumbers,
                temporaryNameOverride, temporaryPriceOverride,
                null, null, null, null, null);
    }

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
                DocumentLineType.PRODUCT, null, null, null, List.of());
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
                promotionId, promotionVersionId, promotionalCouponId, serialNumbers,
                temporaryNameOverride, temporaryPriceOverride,
                returnSourceType, returnSourceCode,
                returnSourceTicketId, originalDocumentLineId, giftReceiptLineId,
                null, null, null);
    }

    public DocumentLineCommand withDiscount(BigDecimal discount, String rate) {
        return new DocumentLineCommand(
                productoId, cantidad, codigo, nombre, rate, precioUnitario, discount,
                impuestosIncluidos, regimenImpuesto, porcentajeImpuesto, lineType,
                promotionId, promotionVersionId, promotionalCouponId, serialNumbers,
                temporaryNameOverride, temporaryPriceOverride,
                returnSourceType, returnSourceCode,
                returnSourceTicketId, originalDocumentLineId, giftReceiptLineId,
                null, null, null);
    }

    static String historicalOpenPriceRate() {
        return HISTORICAL_OPEN_PRICE_RATE;
    }

    static boolean isHistoricalOpenPriceRate(String rate) {
        return HISTORICAL_OPEN_PRICE_RATE.equals(rate);
    }

    public boolean historicalOpenPriceOverride() {
        return isHistoricalOpenPriceRate(tarifa);
    }

    public void requireClientProductLine() {
        var resolvedType = lineType == null ? DocumentLineType.PRODUCT : lineType;
        if (resolvedType != DocumentLineType.PRODUCT
                || promotionId != null
                || promotionVersionId != null
                || promotionalCouponId != null) {
            throw new IllegalArgumentException(
                    "Las lineas de promocion y cupon solo puede generarlas el backend");
        }
        if (productoId == null) {
            throw new IllegalArgumentException("productoId es obligatorio");
        }
        if (giftReceiptLineId != null && originalDocumentLineId == null) {
            throw new IllegalArgumentException(
                    "La linea de ticket regalo necesita un origen fiscal");
        }
        if (originalDocumentLineId != null
                && (returnSourceType == null || returnSourceCode == null
                        || returnSourceCode.isBlank() || returnSourceTicketId == null)) {
            throw new IllegalArgumentException(
                    "La linea de devolucion necesita identificar su documento de origen");
        }
    }

    static DocumentLineCommand from(DocumentLine line) {
        var hasReturnOrigin = line.getOriginalDocumentLineId() != null;
        var frozenAmounts = line.getLineType() != DocumentLineType.RETURN_ADJUSTMENT;
        return new DocumentLineCommand(
                line.getProductoId(), line.getCantidad(), line.getCodigo(),
                line.getNombre(), line.getTarifa(), line.getPrecioUnitario(),
                line.getDescuento(), line.isImpuestosIncluidos(),
                line.getRegimenImpuesto(), line.getPorcentajeImpuesto(),
                line.getLineType(), line.getPromotionId(), line.getPromotionVersionId(),
                line.getPromotionalCouponId(), line.getSerialNumbers(), false, false,
                !hasReturnOrigin
                        ? null
                        : line.getGiftReceiptLineId() == null
                                ? TicketReturnService.ReturnSourceType.TICKET
                                : TicketReturnService.ReturnSourceType.GIFT_RECEIPT,
                null,
                hasReturnOrigin ? line.getDocumento().getId() : null,
                line.getOriginalDocumentLineId(), line.getGiftReceiptLineId(),
                frozenAmounts ? line.getBase() : null,
                frozenAmounts ? line.getImpuesto() : null,
                frozenAmounts ? line.getTotal() : null);
    }

    // Converts validated input into a line with a fiscal snapshot.
    public DocumentLine toEntity(CommercialDocument document, int position) {
        if (lineType == DocumentLineType.RETURN_ADJUSTMENT) {
            return DocumentLine.returnAdjustment(
                    document, position, nombre, precioUnitario, impuestosIncluidos,
                    regimenImpuesto, porcentajeImpuesto);
        }
        if (lineType != null && lineType != DocumentLineType.PRODUCT) {
            if (frozenBase != null || frozenTax != null || frozenTotal != null) {
                if (frozenBase == null || frozenTax == null || frozenTotal == null) {
                    throw new IllegalArgumentException(
                            "el ajuste historico necesita base, impuesto y total");
                }
                return DocumentLine.frozenSpecial(
                        document, position, lineType, nombre, precioUnitario,
                        impuestosIncluidos, regimenImpuesto, porcentajeImpuesto,
                        promotionId, promotionVersionId, promotionalCouponId,
                        frozenBase, frozenTax, frozenTotal);
            }
            return DocumentLine.special(
                    document, position, nombre, precioUnitario, impuestosIncluidos,
                    regimenImpuesto, porcentajeImpuesto, promotionId,
                    promotionVersionId, promotionalCouponId, lineType);
        }
        if ((frozenBase == null) != (frozenTax == null)
                || (frozenBase == null) != (frozenTotal == null)) {
            throw new IllegalArgumentException(
                    "la linea historica necesita base, impuesto y total");
        }
        var line = frozenBase == null
                ? new DocumentLine(
                        document, productoId, position, cantidad, codigo, nombre, tarifa,
                        precioUnitario, descuento, impuestosIncluidos, regimenImpuesto,
                        porcentajeImpuesto)
                : DocumentLine.frozenProduct(
                        document, productoId, position, cantidad, codigo, nombre, tarifa,
                        precioUnitario, descuento, impuestosIncluidos, regimenImpuesto,
                        porcentajeImpuesto, frozenBase, frozenTax, frozenTotal);
        line.assignSerialNumbers(serialNumbers);
        if (originalDocumentLineId != null) {
            line.identifyRefundOf(originalDocumentLineId);
        }
        if (giftReceiptLineId != null) {
            line.identifyGiftReceiptLine(giftReceiptLineId);
        }
        return line;
    }

    DocumentLine toEntity(CommercialDocument document) {
        return toEntity(document, document.getLineas().size() + 1);
    }
}
