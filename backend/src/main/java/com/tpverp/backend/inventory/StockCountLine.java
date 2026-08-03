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
@Table(name = "recuento_stock_linea")
public class StockCountLine {
    @Id private UUID id;
    @Column(name = "recuento_id", nullable = false) private UUID countId;
    @Column(name = "producto_id", nullable = false) private UUID productId;
    @Column(name = "cantidad_esperada", nullable = false, precision = 19, scale = 3) private BigDecimal expectedQuantity;
    @Column(name = "cantidad_contada", nullable = false, precision = 19, scale = 3) private BigDecimal countedQuantity;
    @Column(name = "diferencia_aplicada", precision = 19, scale = 3) private BigDecimal appliedDifference;
    @Version private long version;

    protected StockCountLine() {}
    public StockCountLine(UUID countId, UUID productId, BigDecimal expected, BigDecimal counted) {
        id = UUID.randomUUID();
        this.countId = Objects.requireNonNull(countId);
        this.productId = Objects.requireNonNull(productId);
        update(expected, counted);
    }
    public void update(BigDecimal expected, BigDecimal counted) {
        if (appliedDifference != null) throw new IllegalStateException("La linea de recuento ya fue aplicada");
        expectedQuantity = quantity(expected);
        countedQuantity = quantity(counted);
        if (countedQuantity.signum() < 0) throw new IllegalArgumentException("La cantidad contada no puede ser negativa");
    }
    public void markApplied(BigDecimal difference) { appliedDifference = quantity(difference); }
    public BigDecimal difference() { return countedQuantity.subtract(expectedQuantity).setScale(3); }
    public UUID getId() { return id; }
    public UUID getCountId() { return countId; }
    public UUID getProductId() { return productId; }
    public BigDecimal getExpectedQuantity() { return expectedQuantity; }
    public BigDecimal getCountedQuantity() { return countedQuantity; }
    public BigDecimal getAppliedDifference() { return appliedDifference; }
    private static BigDecimal quantity(BigDecimal value) {
        Objects.requireNonNull(value, "cantidad");
        if (value.stripTrailingZeros().scale() > 3) throw new IllegalArgumentException("message.inventory.quantity_scale");
        return value.setScale(3);
    }
}
