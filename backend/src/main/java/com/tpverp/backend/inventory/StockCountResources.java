package com.tpverp.backend.inventory;

import com.tpverp.backend.catalog.ProductType;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record StockCountResources(List<Warehouse> warehouses, List<Product> products, List<Family> families) {
    public record Warehouse(UUID id, String name, boolean active) {}
    public record Product(UUID id, String code, String barcode, String name, boolean active,
                          ProductType productType, UUID familyId) {}
    public record Family(UUID id, String name) {}
    public record Balance(UUID productId, UUID warehouseId, BigDecimal quantity) {}
}
