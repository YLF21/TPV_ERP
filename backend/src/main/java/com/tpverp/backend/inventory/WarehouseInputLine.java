package com.tpverp.backend.inventory;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "entrada_almacen_linea")
public class WarehouseInputLine {

    @Id
    private UUID id;

    @Column(name = "entrada_id", nullable = false)
    private UUID inputId;

    @Column(name = "producto_id", nullable = false)
    private UUID productId;

    @Column(nullable = false)
    private int cantidad;

    @Version
    private long version;

    protected WarehouseInputLine() {
    }

    public WarehouseInputLine(UUID inputId, UUID productId, int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("La cantidad debe ser positiva");
        }
        this.id = UUID.randomUUID();
        this.inputId = Objects.requireNonNull(inputId, "inputId");
        this.productId = Objects.requireNonNull(productId, "productId");
        this.cantidad = quantity;
    }

    public UUID getProductId() {
        return productId;
    }

    public int getQuantity() {
        return cantidad;
    }
}
