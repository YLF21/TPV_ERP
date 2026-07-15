package com.tpverp.backend.inventory;

import java.time.LocalDate;
import java.util.List;
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
                input.getLines().stream().map(WarehouseInputLineView::from).toList());
    }
}
