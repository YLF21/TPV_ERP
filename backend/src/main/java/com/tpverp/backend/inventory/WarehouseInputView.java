package com.tpverp.backend.inventory;

import com.tpverp.backend.catalog.Product;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record WarehouseInputView(
        UUID id,
        String number,
        UUID storeId,
        UUID warehouseId,
        UUID supplierId,
        LocalDate date,
        String origin,
        String concept,
        WarehouseInputStatus status,
        List<WarehouseInputLineView> lines) {

    public static WarehouseInputView from(WarehouseInput input) {
        return from(input, Map.of());
    }

    public static WarehouseInputView from(WarehouseInput input, Map<UUID, Product> products) {
        return new WarehouseInputView(
                input.getId(),
                input.getNumber(),
                input.getStoreId(),
                input.getWarehouseId(),
                input.getSupplierId(),
                input.getDate(),
                input.getOrigin(),
                input.getConcept(),
                input.getStatus(),
                input.getLines().stream()
                        .map(line -> WarehouseInputLineView.from(line, products.get(line.getProductId())))
                        .toList());
    }
}
