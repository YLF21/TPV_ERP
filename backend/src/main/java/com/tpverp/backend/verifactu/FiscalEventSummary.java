package com.tpverp.backend.verifactu;

import java.math.BigDecimal;

/** Aggregates the NO VERI*FACTU evidence included in a type 10 summary event. */
public record FiscalEventSummary(
        long eventCount,
        long altaCount,
        BigDecimal altaTaxTotal,
        BigDecimal altaAmountTotal,
        long cancellationCount) {

    public FiscalEventSummary {
        if (eventCount < 0 || altaCount < 0 || cancellationCount < 0) {
            throw new IllegalArgumentException("Los contadores del resumen no pueden ser negativos");
        }
        altaTaxTotal = amount(altaTaxTotal);
        altaAmountTotal = amount(altaAmountTotal);
    }

    public static FiscalEventSummary empty() {
        return new FiscalEventSummary(0, 0, BigDecimal.ZERO, BigDecimal.ZERO, 0);
    }

    private static BigDecimal amount(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
