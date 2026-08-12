package com.tpverp.backend.document;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

public record SalesDocumentDraftView(
        UUID id,
        long version,
        CommercialDocumentType type,
        LocalDate date,
        LocalDate dueDate,
        UUID warehouseId,
        UUID customerId,
        String customerName,
        BigDecimal globalDiscount,
        BigDecimal total,
        String internalComment,
        Instant createdAt,
        List<LineView> lines) {

    static SalesDocumentDraftView from(
            CommercialDocument document, String customerName) {
        return new SalesDocumentDraftView(
                document.getId(), document.getVersion(), document.getTipo(),
                document.getFecha(), document.getDueDate(), document.getAlmacenId(),
                document.getClienteId(), customerName, document.getDescuentoGlobal(),
                document.getTotal(), document.getComentarioInterno(), document.getCreadoEn(),
                document.getLineas().stream()
                        .filter(line -> line.getLineType() == DocumentLineType.PRODUCT)
                        .sorted(Comparator.comparingInt(DocumentLine::getPosicion))
                        .map(LineView::from)
                        .toList());
    }

    public record LineView(
            UUID id,
            UUID productId,
            int position,
            BigDecimal quantity,
            String code,
            String barcode,
            String name,
            String rate,
            BigDecimal unitPrice,
            BigDecimal discount,
            boolean taxesIncluded,
            String taxRegime,
            BigDecimal taxPercentage,
            List<String> serialNumbers,
            boolean temporaryNameOverride,
            boolean temporaryPriceOverride) {

        static LineView from(DocumentLine line) {
            return new LineView(
                    line.getId(), line.getProductoId(), line.getPosicion(),
                    line.getCantidad(), line.getCodigo(), line.getCodigoBarras(),
                    line.getNombre(), line.getTarifa(), line.getPrecioUnitario(),
                    line.getDescuento(), line.isImpuestosIncluidos(),
                    line.getRegimenImpuesto(), line.getPorcentajeImpuesto(),
                    line.getSerialNumbers(), line.isTemporaryNameOverride(),
                    line.isTemporaryPriceOverride());
        }
    }
}
