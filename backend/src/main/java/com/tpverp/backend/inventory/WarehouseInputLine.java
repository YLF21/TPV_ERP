package com.tpverp.backend.inventory;

import com.tpverp.backend.document.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
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

    @Column(name = "precio_unitario_compra", nullable = false, precision = 19, scale = 2)
    private BigDecimal purchaseUnitPrice = BigDecimal.ZERO;

    @Version
    private long version;

    protected WarehouseInputLine() {
    }

    public WarehouseInputLine(UUID inputId, UUID productId, int quantity) {
        this(inputId, productId, quantity, BigDecimal.ZERO);
    }

    public WarehouseInputLine(UUID inputId, UUID productId, int quantity, BigDecimal purchaseUnitPrice) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("La cantidad debe ser positiva");
        }
        this.id = UUID.randomUUID();
        this.inputId = Objects.requireNonNull(inputId, "inputId");
        this.productId = Objects.requireNonNull(productId, "productId");
        this.cantidad = quantity;
        this.purchaseUnitPrice = Money.euros(Objects.requireNonNull(purchaseUnitPrice, "purchaseUnitPrice"));
        if (this.purchaseUnitPrice.signum() < 0) {
            throw new IllegalArgumentException("El precio de compra no puede ser negativo");
        }
    }

    public UUID getProductId() {
        return productId;
    }

    public int getQuantity() {
        return cantidad;
    }

    public BigDecimal getPurchaseUnitPrice() {
        return purchaseUnitPrice;
    }

    public BigDecimal getPurchaseTotal() {
        return Money.euros(purchaseUnitPrice.multiply(BigDecimal.valueOf(cantidad)));
    }

    void snapshotPurchaseUnitPrice(BigDecimal purchaseUnitPrice) {
        var normalized = Money.euros(Objects.requireNonNull(purchaseUnitPrice, "purchaseUnitPrice"));
        if (normalized.signum() < 0) {
            throw new IllegalArgumentException("El precio de compra no puede ser negativo");
        }
        this.purchaseUnitPrice = normalized;
    }
}
