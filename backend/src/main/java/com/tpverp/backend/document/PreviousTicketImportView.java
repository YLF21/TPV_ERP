package com.tpverp.backend.document;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import com.tpverp.backend.catalog.ProductType;

public record PreviousTicketImportView(
        UUID ticketId,
        String ticketNumber,
        LocalDate ticketDate,
        DocumentStatus status,
        PreviousTicketImportPricingMode pricingMode,
        UUID customerId,
        String fingerprint,
        BigDecimal globalDiscount,
        BigDecimal baseTotal,
        BigDecimal taxTotal,
        BigDecimal total,
        BigDecimal preservedManualDiscountAmount,
        boolean manualDiscountAuthorizationRequired,
        String currency,
        List<LineView> lines,
        List<AdjustmentView> adjustments) {

    public PreviousTicketImportView {
        lines = List.copyOf(lines);
        adjustments = List.copyOf(adjustments);
    }

    public record LineView(
            UUID sourceLineId,
            UUID productId,
            String code,
            String name,
            BigDecimal quantity,
            String rate,
            BigDecimal unitPrice,
            BigDecimal discount,
            boolean taxesIncluded,
            String taxRegime,
            BigDecimal taxPercent,
            BigDecimal base,
            BigDecimal tax,
            BigDecimal total,
            List<String> serialNumbers,
            ProductType productType,
            boolean manualPricePreserved,
            boolean temporaryPriceAuthorizationRequired,
            boolean requiresNewSerialNumbers) {

        public LineView {
            serialNumbers = List.copyOf(serialNumbers == null ? List.of() : serialNumbers);
        }
    }

    public record AdjustmentView(
            DocumentLineType lineType,
            String name,
            BigDecimal base,
            BigDecimal tax,
            BigDecimal total) {
    }
}
