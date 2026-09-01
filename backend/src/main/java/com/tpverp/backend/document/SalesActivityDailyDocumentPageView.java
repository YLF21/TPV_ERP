package com.tpverp.backend.document;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Paginated daily view plus authoritative totals for the selected period. */
public record SalesActivityDailyDocumentPageView(
        List<SalesActivityDailyRowView> items,
        String nextCursor,
        boolean hasMore,
        long ticketCount,
        long invoiceCount,
        BigDecimal total,
        LocalDate dateFrom,
        LocalDate dateTo,
        LocalDate currentDate) {

}
