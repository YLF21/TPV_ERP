package com.tpverp.backend.inventory;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "existencia")
public class StockLevel {

    @Id
    private UUID id;

    @Column(name = "producto_id", nullable = false)
    private UUID productId;

    @Column(name = "almacen_id", nullable = false)
    private UUID warehouseId;

    @Column(nullable = false, precision = 19, scale = 3)
    private BigDecimal cantidad = BigDecimal.ZERO.setScale(3);

    @Version
    private long version;

    protected StockLevel() {
    }

    public StockLevel(UUID productId, UUID warehouseId) {
        this.id = UUID.randomUUID();
        this.productId = Objects.requireNonNull(productId, "productId");
        this.warehouseId = Objects.requireNonNull(warehouseId, "warehouseId");
    }

    public static StockLevel snapshot(UUID productId, UUID warehouseId, BigDecimal quantity) {
        var stock = new StockLevel(productId, warehouseId);
        stock.cantidad = quantity(quantity);
        return stock;
    }

    public UUID getProductId() {
        return productId;
    }

    public UUID getWarehouseId() {
        return warehouseId;
    }

    public BigDecimal getQuantity() {
        return cantidad;
    }

    public void apply(int delta) {
        apply(BigDecimal.valueOf(delta));
    }

    public void apply(BigDecimal delta) {
        if (quantity(delta).signum() == 0) {
            throw new IllegalArgumentException("El movimiento no puede ser cero");
        }
        // Snapshot derivado: la fuente de verdad es movimiento_stock.
        cantidad = cantidad.add(quantity(delta));
    }

    private static BigDecimal quantity(BigDecimal value) {
        Objects.requireNonNull(value, "cantidad");
        if (value.stripTrailingZeros().scale() > 3) {
            throw new IllegalArgumentException("message.inventory.quantity_scale");
        }
        return value.setScale(3);
    }
}
