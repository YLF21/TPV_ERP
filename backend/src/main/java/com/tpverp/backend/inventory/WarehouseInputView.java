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
        WarehouseInputDocumentType documentType,
        LocalDate date,
        String externalNumber,
        String origin,
        String concept,
        WarehouseInputPriceSource priceSource,
        java.math.BigDecimal globalDiscount,
        java.math.BigDecimal subtotal,
        java.math.BigDecimal total,
        List<UUID> sourceDeliveryNoteIds,
        WarehouseInputStatus status,
        List<WarehouseInputLineView> lines) {

    public WarehouseInputView(
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
        this(id, number, storeId, warehouseId, supplierId,
                WarehouseInputDocumentType.ENTRADA_ALMACEN, date, null, origin, concept,
                WarehouseInputPriceSource.PURCHASE, java.math.BigDecimal.ZERO,
                java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, List.of(), status, lines);
    }

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
                input.getDocumentType(),
                input.getDate(),
                input.getExternalNumber(),
                input.getOrigin(),
                input.getConcept(),
                input.getPriceSource(),
                input.getGlobalDiscount(),
                input.getSubtotal(),
                input.getTotal(),
                input.getSourceDeliveryNoteIds(),
                input.getStatus(),
                input.getLines().stream()
                        .map(line -> WarehouseInputLineView.from(line, products.get(line.getProductId())))
                        .toList());
    }
}
