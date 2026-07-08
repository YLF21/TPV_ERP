package com.tpverp.backend.inventory;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record StockTopSalesRow(
        UUID productId,
        String code,
        String barcode,
        String name,
        UUID familyId,
        String familyName,
        UUID subfamilyId,
        String subfamilyName,
        List<StockTopSalesSupplierView> suppliers,
        BigDecimal soldQuantity,
        BigDecimal netAmount,
        BigDecimal currentStock,
        UUID warehouseId,
        String warehouseName) {
}
