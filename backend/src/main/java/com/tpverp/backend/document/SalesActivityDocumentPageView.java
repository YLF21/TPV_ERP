package com.tpverp.backend.document;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record SalesActivityDocumentPageView(
        List<SalesActivityDocumentRowView> items,
        String nextCursor,
        boolean hasMore,
        long ticketCount,
        long invoiceCount,
        BigDecimal total,
        LocalDate dateFrom,
        LocalDate dateTo,
        LocalDate currentDate) {

    public SalesActivityDocumentPageView(
            List<SalesActivityDocumentRowView> items,
            String nextCursor,
            boolean hasMore,
            long ticketCount,
            long invoiceCount,
            BigDecimal total,
            LocalDate dateFrom,
            LocalDate dateTo) {
        this(items, nextCursor, hasMore, ticketCount, invoiceCount, total,
                dateFrom, dateTo, null);
    }
}
