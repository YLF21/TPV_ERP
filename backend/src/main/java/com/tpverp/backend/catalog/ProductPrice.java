package com.tpverp.backend.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "producto_precio")
public class ProductPrice {

    private static final BigDecimal MIN_OPTIONAL_PRICE = new BigDecimal("0.01");

    @Id
    private UUID id;

    @Column(name = "producto_id", nullable = false)
    private UUID productId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private PriceTier tarifa;

    @Column(precision = 19, scale = 2)
    private BigDecimal importe;

    @Version
    private long version;

    protected ProductPrice() {
    }

    public ProductPrice(UUID productId, PriceTier tier, BigDecimal amount) {
        this.id = UUID.randomUUID();
        this.productId = Objects.requireNonNull(productId, "productId");
        this.tarifa = Objects.requireNonNull(tier, "tier");
        replaceAmount(amount);
    }

    public PriceTier getTier() {
        return tarifa;
    }

    public BigDecimal getAmount() {
        return importe;
    }

    public void replaceAmount(BigDecimal amount) {
        if (tarifa == PriceTier.VENTA) {
            if (amount == null || amount.signum() < 0) {
                throw new IllegalArgumentException("El precio VENTA debe ser mayor o igual que cero");
            }
        } else if (amount != null && amount.compareTo(MIN_OPTIONAL_PRICE) < 0) {
            throw new IllegalArgumentException("El precio opcional debe ser nulo o mayor o igual que 0.01");
        }
        importe = amount;
    }
}
