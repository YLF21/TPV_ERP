package com.tpverp.backend.inventory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record ReplenishmentView(UUID id, long version, UUID productId, UUID warehouseId,
        String warehouseName, String code, String barcode, String name, BigDecimal quantity,
        String reason, BigDecimal stock, BigDecimal purchasePrice, UUID supplierId,
        String supplierName, UUID familyId, String familyName, UUID subfamilyId,
        String subfamilyName, String status, LocalDate orderDate) {}
