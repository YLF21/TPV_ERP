package com.tpverp.backend.document;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

public record ApprovedCardTicketSnapshot(
        UUID storeId, UUID warehouseId, LocalDate date, UUID customerId, boolean wholesaleMode,
        UUID paymentMethodId,
        BigDecimal globalDiscount, BigDecimal baseTotal, BigDecimal taxTotal,
        BigDecimal total, List<DocumentLineCommand> lines, String internalComment,
        HistoricalTicketReplayMetadata historicalReplay,
        List<DocumentAdjustmentSnapshot> adjustments) {
    public ApprovedCardTicketSnapshot {
        lines = List.copyOf(lines == null ? List.of() : lines);
        adjustments = List.copyOf(adjustments == null ? List.of() : adjustments);
    }

    public ApprovedCardTicketSnapshot(
            UUID storeId, UUID warehouseId, LocalDate date, UUID customerId,
            UUID paymentMethodId, BigDecimal globalDiscount, BigDecimal baseTotal,
            BigDecimal taxTotal, BigDecimal total, List<DocumentLineCommand> lines,
            String internalComment, HistoricalTicketReplayMetadata historicalReplay) {
        this(storeId, warehouseId, date, customerId, false, paymentMethodId, globalDiscount,
                baseTotal, taxTotal, total, lines, internalComment, historicalReplay,
                List.of());
    }

    public ApprovedCardTicketSnapshot(
            UUID storeId, UUID warehouseId, LocalDate date, UUID customerId,
            UUID paymentMethodId, BigDecimal globalDiscount, BigDecimal baseTotal,
            BigDecimal taxTotal, BigDecimal total, List<DocumentLineCommand> lines,
            String internalComment, HistoricalTicketReplayMetadata historicalReplay,
            List<DocumentAdjustmentSnapshot> adjustments) {
        this(storeId, warehouseId, date, customerId, false, paymentMethodId,
                globalDiscount, baseTotal, taxTotal, total, lines, internalComment,
                historicalReplay, adjustments);
    }

    public ApprovedCardTicketSnapshot(
            UUID storeId, UUID warehouseId, LocalDate date, UUID customerId,
            boolean wholesaleMode, UUID paymentMethodId, BigDecimal globalDiscount,
            BigDecimal baseTotal, BigDecimal taxTotal, BigDecimal total,
            List<DocumentLineCommand> lines, String internalComment,
            HistoricalTicketReplayMetadata historicalReplay) {
        this(storeId, warehouseId, date, customerId, wholesaleMode, paymentMethodId,
                globalDiscount,
                baseTotal, taxTotal, total, lines, internalComment, historicalReplay,
                List.of());
    }
    public ApprovedCardTicketSnapshot(
            UUID storeId, UUID warehouseId, LocalDate date, UUID customerId,
            UUID paymentMethodId, BigDecimal globalDiscount, BigDecimal baseTotal,
            BigDecimal taxTotal, BigDecimal total, List<DocumentLineCommand> lines,
            String internalComment) {
        this(storeId, warehouseId, date, customerId, false, paymentMethodId,
                globalDiscount, baseTotal, taxTotal, total, lines,
                internalComment, null, List.of());
    }

    public ApprovedCardTicketSnapshot(
            UUID storeId, UUID warehouseId, LocalDate date, UUID customerId, UUID paymentMethodId,
            BigDecimal globalDiscount, BigDecimal baseTotal, BigDecimal taxTotal,
            BigDecimal total, List<DocumentLineCommand> lines) {
        this(storeId, warehouseId, date, customerId, false, paymentMethodId,
                globalDiscount, baseTotal, taxTotal, total, lines, null, null, List.of());
    }

    public static ApprovedCardTicketSnapshot from(CommercialDocument quoted,UUID paymentMethodId) {
        return new ApprovedCardTicketSnapshot(quoted.getTiendaId(),quoted.getAlmacenId(),quoted.getFecha(),
                quoted.getClienteId(), quoted.isWholesaleMode(), paymentMethodId,
                quoted.getDescuentoGlobal(),quoted.getBaseTotal(),quoted.getImpuestoTotal(),
                quoted.getTotal(),quoted.getLineas().stream().map(DocumentLineCommand::from).toList(),
                quoted.getComentarioInterno(), null,
                quoted.getAjustes().stream()
                        .map(adjustment -> DocumentAdjustmentSnapshot.from(
                                adjustment, quoted.getLineas()))
                        .toList());
    }

    public static ApprovedCardTicketSnapshot from(
            CommercialDocument quoted,
            UUID paymentMethodId,
            List<DocumentLineCommand> requestedLines,
            HistoricalTicketReplayMetadata historicalReplay) {
        var base = from(quoted, paymentMethodId, requestedLines);
        return new ApprovedCardTicketSnapshot(
                base.storeId(), base.warehouseId(), base.date(), base.customerId(),
                base.wholesaleMode(), base.paymentMethodId(), base.globalDiscount(), base.baseTotal(),
                base.taxTotal(), base.total(), base.lines(), base.internalComment(),
                historicalReplay, base.adjustments());
    }

    public static ApprovedCardTicketSnapshot from(
            CommercialDocument quoted,
            UUID paymentMethodId,
            List<DocumentLineCommand> requestedLines) {
        var base = from(quoted, paymentMethodId);
        var returnSources = List.copyOf(requestedLines == null ? List.of() : requestedLines)
                .stream()
                .filter(line -> line.originalDocumentLineId() != null)
                .collect(Collectors.toMap(
                        DocumentLineCommand::originalDocumentLineId,
                        Function.identity(),
                        (first, ignored) -> first));
        if (returnSources.isEmpty()) {
            return base;
        }
        return new ApprovedCardTicketSnapshot(
                base.storeId(), base.warehouseId(), base.date(), base.customerId(),
                base.wholesaleMode(), base.paymentMethodId(), base.globalDiscount(), base.baseTotal(),
                base.taxTotal(), base.total(),
                base.lines().stream()
                        .map(line -> withReturnSource(line, returnSources))
                        .toList(),
                base.internalComment(), base.historicalReplay(), base.adjustments());
    }

    public void restoreAdjustments(CommercialDocument document) {
        adjustments.forEach(adjustment -> adjustment.restore(document));
        var linkedLines = adjustments.stream()
                .flatMap(adjustment -> adjustment.lines().stream())
                .map(DocumentAdjustmentSnapshot.LineLink::adjustmentLinePosition)
                .collect(java.util.stream.Collectors.toSet());
        var documentDiscountPositions = document.getLineas().stream()
                .filter(line -> line.getLineType() == DocumentLineType.DOCUMENT_DISCOUNT)
                .map(DocumentLine::getPosicion)
                .collect(java.util.stream.Collectors.toSet());
        if (!linkedLines.equals(documentDiscountPositions)) {
            throw new ApprovedCardSnapshotException(
                    "La instantanea ha perdido los ajustes documentales");
        }
    }

    private static DocumentLineCommand withReturnSource(
            DocumentLineCommand line,
            Map<UUID, DocumentLineCommand> returnSources) {
        if (line.originalDocumentLineId() == null) {
            return line;
        }
        var source = returnSources.get(line.originalDocumentLineId());
        if (source == null) {
            throw new IllegalStateException(
                    "La instantanea de devolucion ha perdido su documento de origen");
        }
        return new DocumentLineCommand(
                line.productoId(), line.cantidad(), line.codigo(), line.nombre(),
                line.tarifa(), line.precioUnitario(), line.descuento(),
                line.impuestosIncluidos(), line.regimenImpuesto(),
                line.porcentajeImpuesto(), line.lineType(), line.promotionId(),
                line.promotionVersionId(), line.promotionalCouponId(),
                line.serialNumbers(), line.temporaryNameOverride(),
                line.temporaryPriceOverride(), source.returnSourceType(),
                source.returnSourceCode(), source.returnSourceTicketId(),
                line.originalDocumentLineId(), source.giftReceiptLineId(),
                line.frozenBase(), line.frozenTax(), line.frozenTotal(),
                line.barcode());
    }
}
