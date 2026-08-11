package com.tpverp.backend.document;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

public record ApprovedCardTicketSnapshot(
        UUID storeId, UUID warehouseId, LocalDate date, UUID customerId, UUID paymentMethodId,
        BigDecimal globalDiscount, BigDecimal baseTotal, BigDecimal taxTotal,
        BigDecimal total, List<DocumentLineCommand> lines, String internalComment,
        HistoricalTicketReplayMetadata historicalReplay) {
    public ApprovedCardTicketSnapshot(
            UUID storeId, UUID warehouseId, LocalDate date, UUID customerId,
            UUID paymentMethodId, BigDecimal globalDiscount, BigDecimal baseTotal,
            BigDecimal taxTotal, BigDecimal total, List<DocumentLineCommand> lines,
            String internalComment) {
        this(storeId, warehouseId, date, customerId, paymentMethodId,
                globalDiscount, baseTotal, taxTotal, total, lines,
                internalComment, null);
    }

    public ApprovedCardTicketSnapshot(
            UUID storeId, UUID warehouseId, LocalDate date, UUID customerId, UUID paymentMethodId,
            BigDecimal globalDiscount, BigDecimal baseTotal, BigDecimal taxTotal,
            BigDecimal total, List<DocumentLineCommand> lines) {
        this(storeId, warehouseId, date, customerId, paymentMethodId, globalDiscount,
                baseTotal, taxTotal, total, lines, null, null);
    }

    public static ApprovedCardTicketSnapshot from(CommercialDocument quoted,UUID paymentMethodId) {
        return new ApprovedCardTicketSnapshot(quoted.getTiendaId(),quoted.getAlmacenId(),quoted.getFecha(),
                quoted.getClienteId(),paymentMethodId,quoted.getDescuentoGlobal(),quoted.getBaseTotal(),quoted.getImpuestoTotal(),
                quoted.getTotal(),quoted.getLineas().stream().map(DocumentLineCommand::from).toList(),
                quoted.getComentarioInterno(), null);
    }

    public static ApprovedCardTicketSnapshot from(
            CommercialDocument quoted,
            UUID paymentMethodId,
            List<DocumentLineCommand> requestedLines,
            HistoricalTicketReplayMetadata historicalReplay) {
        var base = from(quoted, paymentMethodId, requestedLines);
        return new ApprovedCardTicketSnapshot(
                base.storeId(), base.warehouseId(), base.date(), base.customerId(),
                base.paymentMethodId(), base.globalDiscount(), base.baseTotal(),
                base.taxTotal(), base.total(), base.lines(), base.internalComment(),
                historicalReplay);
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
                base.paymentMethodId(), base.globalDiscount(), base.baseTotal(),
                base.taxTotal(), base.total(),
                base.lines().stream()
                        .map(line -> withReturnSource(line, returnSources))
                        .toList(),
                base.internalComment(), base.historicalReplay());
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
