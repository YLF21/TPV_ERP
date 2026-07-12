package com.tpverp.backend.inventory;

import java.math.BigDecimal;
import java.util.UUID;

public record StockSettingsView(
        UUID defaultWarehouseId,
        boolean allowNegativeStock,
        BigDecimal defaultMinimumStock,
        boolean alertsEnabled) {
}
