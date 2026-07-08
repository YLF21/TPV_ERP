package com.tpverp.backend.inventory;

import java.util.UUID;

public record StockTopSalesSupplierView(
        UUID supplierId,
        String supplierCode,
        String supplierName) {
}
