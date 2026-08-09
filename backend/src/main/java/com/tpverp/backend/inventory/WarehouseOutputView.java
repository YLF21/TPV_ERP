package com.tpverp.backend.inventory;

import com.tpverp.backend.catalog.Product;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record WarehouseOutputView(
        UUID id,
        String number,
        UUID storeId,
        UUID warehouseId,
        LocalDate date,
        String destination,
        String concept,
        WarehouseOutputStatus status,
        List<WarehouseOutputLineView> lines) {

    public static WarehouseOutputView from(WarehouseOutput output) {
        return from(output, Map.of());
    }

    public static WarehouseOutputView from(WarehouseOutput output, Map<UUID, Product> products) {
        return new WarehouseOutputView(
                output.getId(),
                output.getNumber(),
                output.getStoreId(),
                output.getWarehouseId(),
                output.getDate(),
                output.getDestination(),
                output.getConcept(),
                output.getStatus(),
                output.getLines().stream()
                        .map(line -> WarehouseOutputLineView.from(line, products.get(line.getProductId())))
                        .toList());
    }
}
