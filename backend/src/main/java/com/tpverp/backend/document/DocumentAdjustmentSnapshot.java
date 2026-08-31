package com.tpverp.backend.document;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record DocumentAdjustmentSnapshot(
        String type,
        int order,
        BigDecimal percent,
        BigDecimal eligibleBase,
        BigDecimal appliedAmount,
        UUID userId,
        Instant createdAt,
        UUID memberId,
        UUID memberCategoryId,
        String memberCategoryName,
        List<LineLink> lines) {

    public DocumentAdjustmentSnapshot {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(percent, "percent");
        Objects.requireNonNull(eligibleBase, "eligibleBase");
        Objects.requireNonNull(appliedAmount, "appliedAmount");
        Objects.requireNonNull(createdAt, "createdAt");
        lines = List.copyOf(Objects.requireNonNull(lines, "lines"));
        if (Money.euros(appliedAmount).signum() != 0 && lines.isEmpty()) {
            throw new IllegalArgumentException("document_adjustment_without_lines");
        }
    }

    static DocumentAdjustmentSnapshot from(
            DocumentAdjustment adjustment,
            List<DocumentLine> documentLines) {
        var links = documentLines.stream()
                .filter(line -> adjustment.getId().equals(line.getDocumentAdjustmentId()))
                .map(line -> new LineLink(
                        line.getPosicion(),
                        documentLines.stream()
                                .filter(source -> source.getId().equals(line.getSourceLineId()))
                                .map(DocumentLine::getPosicion)
                                .findFirst()
                                .orElseThrow(() -> new IllegalStateException(
                                        "document_discount_source_line_missing"))))
                .toList();
        if (links.isEmpty() && adjustment.getImporteAplicado().signum() != 0) {
            throw new IllegalStateException("document_adjustment_without_lines");
        }
        return new DocumentAdjustmentSnapshot(
                adjustment.getTipo(), adjustment.getOrden(), adjustment.getPorcentaje(),
                adjustment.getBaseElegible(), adjustment.getImporteAplicado(),
                adjustment.getUsuarioId(), adjustment.getCreadoEn(), adjustment.getMemberId(),
                adjustment.getMemberCategoryId(), adjustment.getMemberCategoryName(), links);
    }

    void restore(CommercialDocument document) {
        var adjustment = new DocumentAdjustment(
                document, type, order, percent, eligibleBase, appliedAmount, userId,
                createdAt, memberId, memberCategoryId, memberCategoryName);
        document.addAdjustment(adjustment);
        var documentLines = document.getLineas();
        for (var link : lines) {
            var discountLine = lineAt(documentLines, link.adjustmentLinePosition());
            var sourceLine = lineAt(documentLines, link.sourceLinePosition());
            if (discountLine.getLineType() != DocumentLineType.DOCUMENT_DISCOUNT
                    || sourceLine.getLineType() == DocumentLineType.DOCUMENT_DISCOUNT) {
                throw new ApprovedCardSnapshotException(
                        "Enlaces de descuento documental invalidos");
            }
            discountLine.linkDocumentAdjustment(adjustment.getId(), sourceLine.getId());
        }
    }

    DocumentAdjustmentSnapshot remapPositions(Map<Integer, Integer> positions) {
        if (lines.isEmpty()) {
            return this;
        }
        var remapped = lines.stream()
                .filter(link -> positions.containsKey(link.adjustmentLinePosition())
                        && positions.containsKey(link.sourceLinePosition()))
                .map(link -> new LineLink(
                        positions.get(link.adjustmentLinePosition()),
                        positions.get(link.sourceLinePosition())))
                .toList();
        return remapped.isEmpty() ? null : new DocumentAdjustmentSnapshot(
                type, order, percent, eligibleBase, appliedAmount, userId, createdAt,
                memberId, memberCategoryId, memberCategoryName, remapped);
    }

    DocumentAdjustmentSnapshot withOrder(int newOrder) {
        return new DocumentAdjustmentSnapshot(
                type, newOrder, percent, eligibleBase, appliedAmount, userId, createdAt,
                memberId, memberCategoryId, memberCategoryName, lines);
    }

    private static DocumentLine lineAt(List<DocumentLine> lines, int position) {
        return lines.stream()
                .filter(line -> line.getPosicion() == position)
                .findFirst()
                .orElseThrow(() -> new ApprovedCardSnapshotException(
                        "Posicion de descuento documental inexistente"));
    }

    public record LineLink(int adjustmentLinePosition, int sourceLinePosition) {
        public LineLink {
            if (adjustmentLinePosition <= 0 || sourceLinePosition <= 0) {
                throw new IllegalArgumentException("document adjustment line positions must be positive");
            }
        }
    }
}
