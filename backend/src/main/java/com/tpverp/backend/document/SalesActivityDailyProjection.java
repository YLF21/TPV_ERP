package com.tpverp.backend.document;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Repository projection for the server-side, paginated daily aggregate. */
public interface SalesActivityDailyProjection {

    LocalDate getDate();

    Long getTicketCount();

    Long getInvoiceCount();

    BigDecimal getTotal();
}
