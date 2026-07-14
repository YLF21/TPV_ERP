package com.tpverp.backend.inventory;

import java.time.LocalDate;
import java.util.List;
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
        return new WarehouseOutputView(
                output.getId(),
                output.getNumber(),
                output.getStoreId(),
                output.getWarehouseId(),
                output.getDate(),
                output.getDestination(),
                output.getConcept(),
                output.getStatus(),
                output.getLines().stream().map(WarehouseOutputLineView::from).toList());
    }
}
