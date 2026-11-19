package com.tpverp.saas.reportexport;

import static com.tpverp.saas.reportexport.ReportExportLayout.label;

import com.tpverp.saas.analytics.ReportStatistics;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** A presentation of existing report values. All totals and comparisons come from the report services. */
record ReadableReportLayout(List<String> columns, String groupBy, boolean comparing, String language) {
    static final Set<String> STATISTICS_VIEWS = Set.of("sales", "sync", "averages", "provinces");

    static ReadableReportLayout of(String resource, String kind, String view, Map<String, Object> metadata,
            String language) {
        boolean comparing = metadata.get("comparisonPeriod") != null;
        List<String> columns = switch (resource) {
            case "statistics" -> statistics(view, (ReportStatistics.PeriodPair) metadata.get("totals"), comparing);
            case "articles" -> {
                var keys = new ArrayList<>(List.of("companyName", "storeName", "productCode", "productName",
                        "currency", "current.netQuantity", "current.netAmount"));
                if (comparing) {
                    keys.addAll(List.of("comparison.netQuantity", "comparison.netAmount", "deltaQuantity",
                            "deltaAmount", "deltaPercent"));
                }
                yield keys;
            }
            case "documents" -> List.of("businessDate", "number", "type", "status", "companyName", "storeName",
                    "currency", "total", "eligible");
            case "movements" -> kind.equals("ledger")
                    ? List.of("occurredAt", "companyName", "storeName", "warehouseName", "productName", "type", "quantity")
                    : comparing ? List.of("label", "comparisonLabel", "currentCount", "comparisonCount")
                            : List.of("label", "currentCount");
            case "comparison" -> List.of("code", "name", "warehouseName", "status", "previousQuantity",
                    "currentQuantity", "delta", "percent");
            case "inventory" -> inventory(kind);
            default -> throw new IllegalArgumentException("Informe desconocido");
        };
        String grouping = metadata.getOrDefault("groupBy", "").toString();
        if (!Set.of("DAY", "WEEK", "MONTH").contains(grouping)) {
            columns = columns.stream().filter(key -> !key.equals("comparisonLabel")).toList();
        }
        return new ReadableReportLayout(List.copyOf(columns), grouping, comparing, language);
    }

    private static List<String> statistics(String view, ReportStatistics.PeriodPair totals, boolean comparing) {
        var columns = new ArrayList<>(List.of("label"));
        if (comparing) {
            columns.add("comparisonLabel");
        }
        var sides = comparing ? List.of("current", "comparison") : List.of("current");
        if (view.equals("sync")) {
            for (String side : sides) {
                for (String key : List.of("syncEvents", "syncErrors", "stockMovementEvents")) {
                    columns.add(side + "." + key);
                }
            }
            return columns;
        }
        var currencies = new TreeSet<String>();
        totals.current().currencies().forEach(metric -> currencies.add(metric.currency()));
        if (totals.comparison() != null) {
            totals.comparison().currencies().forEach(metric -> currencies.add(metric.currency()));
        }
        boolean averages = view.equals("averages");
        var metrics = averages ? List.of("averageAmount", "amountPerDay", "amountPerStore") : List.of("amount");
        // An empty period still has identifiable monetary columns, with no assumed currency or zero amount.
        if (currencies.isEmpty()) {
            for (String metric : metrics) {
                for (String side : sides) {
                    columns.add(side + "." + (metric.equals("amount") ? "netAmount" : metric));
                }
            }
        }
        for (String currency : currencies) {
            for (String metric : metrics) {
                for (String side : sides) {
                    columns.add(side + ".currencies." + currency + "." + metric);
                }
            }
            if (comparing && !averages) {
                columns.add("deltas." + currency + ".amount");
                columns.add("deltas." + currency + ".percent");
            }
        }
        for (String side : sides) {
            columns.add(side + ".eligibleDocuments");
        }
        return columns;
    }

    private static List<String> inventory(String kind) {
        return switch (kind) {
            case "overview" -> List.of("companyName", "storeName", "snapshot.capturedAt", "snapshot.productCount",
                    "snapshot.activeProductCount", "snapshot.warehouseCount", "snapshot.balanceCount");
            case "provinces" -> List.of("province", "totals.stores", "totals.reportedStores", "totals.products",
                    "totals.activeProducts", "totals.warehouses", "totals.balances");
            case "history" -> List.of("capturedAt", "completedAt", "currentInstallation", "productCount",
                    "activeProductCount", "warehouseCount", "balanceCount");
            case "products" -> List.of("code", "name", "barcode", "active", "productType", "familyName", "subfamilyName");
            case "warehouses" -> List.of("name", "active", "defaultWarehouse");
            case "balances" -> List.of("code", "name", "warehouseName", "productType", "quantity");
            default -> throw new IllegalArgumentException("Tipo de informe de inventario desconocido");
        };
    }

    String header(String key) {
        String[] parts = key.split("\\.");
        String metric = parts[parts.length - 1];
        if (key.equals("label") && !groupBy.isEmpty()) {
            return label(groupBy, language);
        }
        if (parts[0].equals("deltas")) {
            return label(metric.equals("amount") ? "deltaAmount" : "deltaPercent", language) + " (" + parts[1] + ")";
        }
        if (parts.length == 4 && parts[1].equals("currencies") && metric.equals("amount")) {
            metric = "netAmount";
        }
        String title = label(metric, language);
        if (comparing && (parts[0].equals("current") || parts[0].equals("comparison"))) {
            title += " · " + label(parts[0].equals("current") ? "current" : "comparison", language);
        }
        return parts.length == 4 && parts[1].equals("currencies") ? title + " (" + parts[2] + ")" : title;
    }

    Object cell(Map<String, Object> row, String column) {
        Object value = row.get(column);
        if (value == null) {
            return null;
        }
        return switch (column) {
            case "type", "status", "productType", "eligible", "active", "currentInstallation", "defaultWarehouse" ->
                    ReportExportLayout.valueLabel(value.toString(), language);
            case "label" -> groupBy.equals("TYPE") ? ReportExportLayout.valueLabel(value.toString(), language) : value;
            default -> value;
        };
    }
}
