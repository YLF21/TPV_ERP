package com.tpverp.backend.inventory;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Additional exact choices for the stock list and its exports. */
public record StockListFilterChoices(
        List<String> productTypes, List<String> priceUseModes, List<UUID> familyIds, List<UUID> taxIds,
        List<Boolean> offerStates, List<String> stockStatuses, List<UUID> supplierIds,
        List<UUID> warehouseIds, List<UUID> topSalesWarehouseIds, List<UUID> topSalesFamilyIds, List<UUID> topSalesSubfamilyIds) {
    public static final StockListFilterChoices EMPTY =
            new StockListFilterChoices(null, null, null, null, null, null, null, null, null, null, null);

    public StockListFilterChoices {
        productTypes = uppercase(productTypes);
        priceUseModes = uppercase(priceUseModes);
        stockStatuses = uppercase(stockStatuses);
        if (!Set.of("OK", "LOW", "EMPTY", "INACTIVE").containsAll(stockStatuses)) {
            throw new IllegalArgumentException("Estado de stock no valido");
        }
        familyIds = copy(familyIds);
        taxIds = copy(taxIds);
        offerStates = copy(offerStates);
        supplierIds = copy(supplierIds);
        warehouseIds = copy(warehouseIds);
        topSalesWarehouseIds = copy(topSalesWarehouseIds);
        topSalesFamilyIds = copy(topSalesFamilyIds);
        topSalesSubfamilyIds = copy(topSalesSubfamilyIds);
    }

    public boolean isEmpty() {
        return productTypes.isEmpty() && priceUseModes.isEmpty() && familyIds.isEmpty() && taxIds.isEmpty()
                && offerStates.isEmpty() && stockStatuses.isEmpty() && supplierIds.isEmpty() && warehouseIds.isEmpty();
    }

    public static String sqlArray(List<?> values) {
        return values.isEmpty() ? null : values.stream().map(Object::toString).collect(Collectors.joining(","));
    }

    private static List<String> uppercase(List<String> values) {
        return copy(values).stream().map(value -> {
            var normalized = value.trim().toUpperCase(Locale.ROOT);
            if (!normalized.matches("[A-Z_]+")) throw new IllegalArgumentException("Filtro de producto no valido");
            return normalized;
        }).distinct().toList();
    }

    private static <T> List<T> copy(List<T> values) {
        if (values == null) return List.of();
        if (values.size() > 500) throw new IllegalArgumentException("Demasiadas opciones de filtro");
        return List.copyOf(values);
    }
}
