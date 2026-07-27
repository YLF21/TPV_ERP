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

    @Column(name = "precio_unitario_venta", nullable = false, precision = 19, scale = 2)
    private BigDecimal saleUnitPrice = BigDecimal.ZERO;

    @Version
    private long version;

    protected WarehouseOutputLine() {
    }

    public WarehouseOutputLine(UUID outputId, UUID productId, int quantity) {
        this(outputId, productId, quantity, BigDecimal.ZERO);
    }

    public WarehouseOutputLine(UUID outputId, UUID productId, int quantity, BigDecimal saleUnitPrice) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("La cantidad debe ser positiva");
        }
        this.id = UUID.randomUUID();
        this.outputId = Objects.requireNonNull(outputId, "outputId");
        this.productId = Objects.requireNonNull(productId, "productId");
        this.cantidad = quantity;
        this.saleUnitPrice = Money.euros(Objects.requireNonNull(saleUnitPrice, "saleUnitPrice"));
        if (this.saleUnitPrice.signum() < 0) {
            throw new IllegalArgumentException("El precio de venta no puede ser negativo");
        }
    }

    public UUID getProductId() {
        return productId;
    }

    public int getQuantity() {
        return cantidad;
    }

    public BigDecimal getSaleUnitPrice() {
        return saleUnitPrice;
    }

    public BigDecimal getSaleTotal() {
        return Money.euros(saleUnitPrice.multiply(BigDecimal.valueOf(cantidad)));
    }

    void snapshotSaleUnitPrice(BigDecimal saleUnitPrice) {
        var normalized = Money.euros(Objects.requireNonNull(saleUnitPrice, "saleUnitPrice"));
        if (normalized.signum() < 0) {
            throw new IllegalArgumentException("El precio de venta no puede ser negativo");
        }
        this.saleUnitPrice = normalized;
    }
}
