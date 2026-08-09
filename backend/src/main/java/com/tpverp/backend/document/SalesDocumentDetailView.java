package com.tpverp.backend.document;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

public record SalesDocumentDetailView(
        UUID id,
        CommercialDocumentType type,
        DocumentStatus status,
        String number,
        LocalDate date,
        BigDecimal base,
        BigDecimal tax,
        BigDecimal discount,
        BigDecimal total,
        RelatedDocumentView originTicket,
        List<LineView> lines) {

    static SalesDocumentDetailView from(CommercialDocument document) {
        return from(document, null);
    }

    static SalesDocumentDetailView from(
            CommercialDocument document,
            CommercialDocument originTicket) {
        return new SalesDocumentDetailView(
                document.getId(),
                document.getTipo(),
                document.getEstado(),
                document.getNumero(),
                document.getFecha(),
                document.getBaseTotal(),
                document.getImpuestoTotal(),
                document.getDescuentoGlobal(),
                document.getTotal(),
                originTicket == null ? null : RelatedDocumentView.from(originTicket),
                document.getLineas().stream()
                        .sorted(Comparator.comparingInt(DocumentLine::getPosicion))
                        .map(LineView::from)
                        .toList());
    }

    public record RelatedDocumentView(UUID id, String number) {
        static RelatedDocumentView from(CommercialDocument document) {
            return new RelatedDocumentView(document.getId(), document.getNumero());
        }
    }

    public record LineView(
            UUID id,
            int position,
            String code,
            String name,
            BigDecimal quantity,
            BigDecimal unitPrice,
            BigDecimal discount,
            String taxRegime,
            BigDecimal taxPercentage,
            BigDecimal total) {

        static LineView from(DocumentLine line) {
            return new LineView(
                    line.getId(),
                    line.getPosicion(),
                    line.getCodigo(),
                    line.getNombre(),
                    line.getCantidad(),
                    line.getPrecioUnitario(),
                    line.getDescuento(),
                    line.getRegimenImpuesto(),
                    line.getPorcentajeImpuesto(),
                    line.getTotal());
        }
    }
}
