package com.tpverp.backend.document;

import java.math.BigDecimal;

public interface SalesActivityDailyTotalsProjection {

    Long getTicketCount();

    Long getInvoiceCount();

    BigDecimal getTotal();
}
