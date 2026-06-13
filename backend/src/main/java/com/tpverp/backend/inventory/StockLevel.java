package com.tpverp.backend.inventory;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
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

    @Column(nullable = false)
    private int cantidad;

    @Version
    private long version;

    protected StockLevel() {
    }

    public StockLevel(UUID productId, UUID warehouseId) {
        this.id = UUID.randomUUID();
        this.productId = Objects.requireNonNull(productId, "productId");
        this.warehouseId = Objects.requireNonNull(warehouseId, "warehouseId");
    }

    public UUID getProductId() {
        return productId;
    }

    public UUID getWarehouseId() {
        return warehouseId;
    }

    public int getQuantity() {
        return cantidad;
    }

    public void apply(int delta) {
        if (delta == 0) {
            throw new IllegalArgumentException("El movimiento no puede ser cero");
        }
        cantidad = Math.addExact(cantidad, delta);
    }
}
