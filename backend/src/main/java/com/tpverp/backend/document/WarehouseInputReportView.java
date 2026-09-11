package com.tpverp.backend.document;

import com.tpverp.backend.inventory.WarehouseInputView;

public record WarehouseInputReportView(
        WarehouseInputView document,
        String supplierCode,
        String supplierName,
        String warehouseName) {
}
