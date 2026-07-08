package com.tpverp.backend.inventory;

import java.time.LocalDate;
import java.util.Locale;

public enum StockTopSalesPeriod {
    DAY("day", 1),
    WEEK("week", 7),
    MONTH("month", 30),
    YEAR("year", 365);

    private final String code;
    private final int days;

    StockTopSalesPeriod(String code, int days) {
        this.code = code;
        this.days = days;
    }

    public LocalDate startDate(LocalDate date) {
        return date.minusDays(days - 1L);
    }

    public static StockTopSalesPeriod fromCode(String value) {
        if (value == null || value.isBlank()) {
            return WEEK;
        }
        var normalized = value.trim().toLowerCase(Locale.ROOT);
        for (var period : values()) {
            if (period.code.equals(normalized)) {
                return period;
            }
        }
        throw new IllegalArgumentException("period no valido");
    }
}
