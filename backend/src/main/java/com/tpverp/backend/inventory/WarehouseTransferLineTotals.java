package com.tpverp.backend.inventory;

import java.math.BigDecimal;
import java.util.UUID;

public record WarehouseTransferLineTotals(UUID transferId, long lineCount, BigDecimal totalUnits) {}
