package com.tpverp.backend.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "producto_precio_historial")
public class ProductPriceHistory {

    @Id
    private UUID id;

    @Column(name = "producto_id", nullable = false)
    private UUID productId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ProductPriceHistoryType tipo;

    @Column(precision = 19, scale = 2)
    private BigDecimal importe;

    @Column(name = "actualizado_en", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected ProductPriceHistory() {
    }

    public ProductPriceHistory(
            UUID productId,
            ProductPriceHistoryType type,
            BigDecimal amount,
            Instant updatedAt) {
        this.id = UUID.randomUUID();
        this.productId = Objects.requireNonNull(productId, "productId");
        this.tipo = Objects.requireNonNull(type, "type");
        this.importe = amount;
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    }

    public UUID getId() {
        return id;
    }

    public UUID getProductId() {
        return productId;
    }

    public ProductPriceHistoryType getType() {
        return tipo;
    }

    public BigDecimal getAmount() {
        return importe;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
