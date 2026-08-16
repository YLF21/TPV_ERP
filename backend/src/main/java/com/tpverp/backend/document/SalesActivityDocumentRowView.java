package com.tpverp.backend.document;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record SalesActivityDocumentRowView(
        UUID id,
        LocalDate date,
        Instant occurredAt,
        String ticketNumber,
        String invoiceNumber,
        UUID userId,
        String userName,
        List<String> paymentMethods,
        SalesActivityKind kind,
        DocumentStatus status,
        BigDecimal total) {

    public enum SalesActivityKind {
        SALE,
        RETURN,
        CANCELLED
    }
}
