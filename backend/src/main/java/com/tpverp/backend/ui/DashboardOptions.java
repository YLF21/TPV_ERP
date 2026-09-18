package com.tpverp.backend.ui;

import java.util.Set;

/** User presentation choices; these never change the sales calculation rules. */
public record DashboardOptions(
        String defaultPeriod,
        String trendDisplay,
        String productDisplay,
        String density,
        Boolean showComparison) {

    public DashboardOptions {
        require(defaultPeriod, Set.of("TODAY", "LAST_7_DAYS", "LAST_30_DAYS", "MONTH"), "defaultPeriod");
        require(trendDisplay, Set.of("LINE", "BAR", "TABLE"), "trendDisplay");
        require(productDisplay, Set.of("BAR", "TABLE"), "productDisplay");
        require(density, Set.of("COMFORTABLE", "COMPACT"), "density");
        if (showComparison == null) {
            throw new IllegalArgumentException("options.showComparison es obligatorio");
        }
    }

    public static DashboardOptions defaults() {
        return new DashboardOptions("MONTH", "LINE", "BAR", "COMFORTABLE", true);
    }

    private static void require(String value, Set<String> allowed, String field) {
        if (value == null || !allowed.contains(value)) {
            throw new IllegalArgumentException("options." + field + " no es valido");
        }
    }
}
