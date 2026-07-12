package com.tpverp.backend.inventory;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(
        name = "stock_minimo_almacen",
        uniqueConstraints = @UniqueConstraint(
                name = "ux_stock_minimo_producto_almacen",
                columnNames = {"producto_id", "almacen_id"}))
public class StockMinimum {

    @Id
    private UUID id;

    @Column(name = "tienda_id", nullable = false)
    private UUID storeId;

    @Column(name = "producto_id", nullable = false)
    private UUID productId;

    @Column(name = "almacen_id", nullable = false)
    private UUID warehouseId;

    @Column(name = "cantidad_minima", nullable = false, precision = 19, scale = 3)
    private BigDecimal minimumStock;

    @Version
    private long version;

    protected StockMinimum() {
    }

    public StockMinimum(
            UUID storeId, UUID productId, UUID warehouseId, BigDecimal minimumStock) {
        this.id = UUID.randomUUID();
        this.storeId = Objects.requireNonNull(storeId, "storeId");
        this.productId = Objects.requireNonNull(productId, "productId");
        this.warehouseId = Objects.requireNonNull(warehouseId, "warehouseId");
        this.minimumStock = StockSettings.minimum(minimumStock);
    }

    public void update(BigDecimal minimumStock) {
        this.minimumStock = StockSettings.minimum(minimumStock);
    }

    public UUID getStoreId() {
        return storeId;
    }

    public UUID getProductId() {
        return productId;
    }

    public UUID getWarehouseId() {
        return warehouseId;
    }

    public BigDecimal getMinimumStock() {
        return minimumStock;
    }
}
