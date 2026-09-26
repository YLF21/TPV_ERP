package com.tpverp.backend.ui;

import java.util.Set;

/** User presentation choices; these never change the sales calculation rules. */
public record DashboardOptions(
        String defaultPeriod,
        String trendDisplay,
        String productDisplay,
        String density,
        Boolean showComparison,
        String familyDisplay,
        String productSort,
        String alertDisplay,
        String promotionDisplay,
        String hourlyDisplay,
        String correctionDisplay,
        String paymentDisplay,
        String receivableDisplay) {

    public DashboardOptions {
        require(defaultPeriod, Set.of("TODAY", "LAST_7_DAYS", "LAST_30_DAYS", "MONTH"), "defaultPeriod");
        require(trendDisplay, Set.of("LINE", "BAR", "TABLE"), "trendDisplay");
        require(productDisplay, Set.of("BAR", "TABLE"), "productDisplay");
        require(density, Set.of("COMFORTABLE", "COMPACT"), "density");
        if (showComparison == null) {
            throw new IllegalArgumentException("options.showComparison es obligatorio");
        }
        // Older persisted preferences and clients omit these fields.
        familyDisplay = familyDisplay == null ? "BAR" : familyDisplay;
        productSort = productSort == null ? "QUANTITY" : productSort;
        alertDisplay = alertDisplay == null ? "TABLE" : alertDisplay;
        promotionDisplay = promotionDisplay == null ? "TABLE" : promotionDisplay;
        require(familyDisplay, Set.of("BAR", "TABLE"), "familyDisplay");
        require(productSort, Set.of("QUANTITY", "AMOUNT"), "productSort");
        require(alertDisplay, Set.of("BAR", "TABLE"), "alertDisplay");
        require(promotionDisplay, Set.of("BAR", "TABLE"), "promotionDisplay");
        hourlyDisplay = optionalDisplay(hourlyDisplay == null ? "BAR" : hourlyDisplay, "hourlyDisplay");
        correctionDisplay = optionalDisplay(correctionDisplay, "correctionDisplay");
        paymentDisplay = optionalDisplay(paymentDisplay, "paymentDisplay");
        receivableDisplay = optionalDisplay(receivableDisplay, "receivableDisplay");
    }

    public DashboardOptions(String defaultPeriod, String trendDisplay, String productDisplay,
            String density, Boolean showComparison, String familyDisplay, String productSort,
            String alertDisplay, String promotionDisplay) {
        this(defaultPeriod, trendDisplay, productDisplay, density, showComparison, familyDisplay, productSort,
                alertDisplay, promotionDisplay, "BAR", "TABLE", "TABLE", "TABLE");
    }

    private static String optionalDisplay(String value, String field) {
        var display = value == null ? "TABLE" : value;
        require(display, Set.of("BAR", "TABLE"), field);
        return display;
    }

    public DashboardOptions(String defaultPeriod, String trendDisplay, String productDisplay,
            String density, Boolean showComparison) {
        this(defaultPeriod, trendDisplay, productDisplay, density, showComparison, "BAR", "QUANTITY", "TABLE", "TABLE");
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
