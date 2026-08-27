package com.tpverp.backend.verifactu;

import java.math.BigDecimal;
import java.time.Instant;

/** SQL aggregate used to build the next periodic fiscal summary without materializing history. */
record FiscalEventSummaryAggregate(
        Instant previousSummaryAt,
        long eventCount,
        long altaCount,
        BigDecimal totalTax,
        BigDecimal totalAmount,
        long cancellationCount) {
}
