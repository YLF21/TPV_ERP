package com.tpverp.backend.document;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record SalesDocumentDraftSummaryView(
        UUID id,
        long version,
        CommercialDocumentType type,
        LocalDate date,
        UUID customerId,
        String customerName,
        BigDecimal total,
        Instant createdAt) {

    static SalesDocumentDraftSummaryView from(
            CommercialDocument document, String customerName) {
        return new SalesDocumentDraftSummaryView(
                document.getId(), document.getVersion(), document.getTipo(),
                document.getFecha(), document.getClienteId(), customerName,
                document.getTotal(), document.getCreadoEn());
    }
}
