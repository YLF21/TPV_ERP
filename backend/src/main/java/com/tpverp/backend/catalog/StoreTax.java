package com.tpverp.backend.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "impuesto_tienda")
public class StoreTax {

    @Id
    private UUID id;

    @Column(name = "tienda_id", nullable = false)
    private UUID storeId;

    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal porcentaje;

    @Column(nullable = false)
    private boolean activo = true;

    @Column(nullable = false)
    private boolean predeterminado;

    @Version
    private long version;

    protected StoreTax() {
    }

    public StoreTax(UUID storeId, BigDecimal percentage, boolean defaultTax) {
        this.id = UUID.randomUUID();
        this.storeId = Objects.requireNonNull(storeId, "storeId");
        this.porcentaje = validPercentage(percentage);
        this.predeterminado = defaultTax;
    }

    public UUID getId() {
        return id;
    }

    public UUID getStoreId() {
        return storeId;
    }

    public BigDecimal getPercentage() {
        return porcentaje;
    }

    public boolean isActive() {
        return activo;
    }

    public boolean isDefaultTax() {
        return predeterminado;
    }

    public void deactivate() {
        if (predeterminado) {
            throw new IllegalStateException("El impuesto predeterminado no se puede desactivar");
        }
        activo = false;
    }

    public void activate() {
        activo = true;
    }

    public void markDefault() {
        activo = true;
        predeterminado = true;
    }

    public void clearDefault() {
        predeterminado = false;
    }

    public void replacePercentage(BigDecimal percentage) {
        this.porcentaje = validPercentage(percentage);
    }

    public void requireSelectable() {
        if (!activo) {
            throw new IllegalStateException("El impuesto seleccionado no esta activo");
        }
    }

    public void requireDeletable() {
        if (predeterminado) {
            throw new IllegalStateException("El impuesto predeterminado no se puede eliminar");
        }
    }

    private static BigDecimal validPercentage(BigDecimal value) {
        Objects.requireNonNull(value, "percentage");
        if (value.signum() < 0 || value.compareTo(new BigDecimal("100")) > 0) {
            throw new IllegalArgumentException("El porcentaje debe estar entre 0 y 100");
        }
        return value;
    }
}
