package com.tpverp.backend.document;

import java.math.BigDecimal;
import java.time.LocalDate;

/** One authoritative sales-activity aggregate for an issue date. */
public record SalesActivityDailyRowView(
        LocalDate date,
        long ticketCount,
        long invoiceCount,
        BigDecimal total) {

}
