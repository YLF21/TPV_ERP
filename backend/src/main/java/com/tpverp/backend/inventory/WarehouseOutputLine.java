package com.tpverp.backend.inventory;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "salida_almacen_linea")
public class WarehouseOutputLine {

    @Id
    private UUID id;

    @Column(name = "salida_id", nullable = false)
    private UUID outputId;

    @Column(name = "producto_id", nullable = false)
    private UUID productId;

    @Column(nullable = false)
    private int cantidad;

    @Version
    private long version;

    protected WarehouseOutputLine() {
    }

    public WarehouseOutputLine(UUID outputId, UUID productId, int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("La cantidad debe ser positiva");
        }
        this.id = UUID.randomUUID();
        this.outputId = Objects.requireNonNull(outputId, "outputId");
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
