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
@Table(name = "configuracion_stock")
public class StockSettings {

    public static final BigDecimal DEFAULT_MINIMUM_STOCK = new BigDecimal("5.000");

    @Id
    @Column(name = "tienda_id")
    private UUID storeId;

    @Column(name = "almacen_predeterminado_id", nullable = false)
    private UUID defaultWarehouseId;

    @Column(name = "permitir_stock_negativo", nullable = false)
    private boolean allowNegativeStock = true;

    @Column(name = "stock_minimo_predeterminado", nullable = false, precision = 19, scale = 3)
    private BigDecimal defaultMinimumStock = DEFAULT_MINIMUM_STOCK;

    @Column(name = "alertas_habilitadas", nullable = false)
    private boolean alertsEnabled = true;

    @Version
    private long version;

    protected StockSettings() {
    }

    public StockSettings(UUID storeId, UUID defaultWarehouseId) {
        this.storeId = Objects.requireNonNull(storeId, "storeId");
        this.defaultWarehouseId = Objects.requireNonNull(defaultWarehouseId, "defaultWarehouseId");
    }

    public void update(
            UUID defaultWarehouseId,
            boolean allowNegativeStock,
            BigDecimal defaultMinimumStock,
            boolean alertsEnabled) {
        this.defaultWarehouseId = Objects.requireNonNull(defaultWarehouseId, "defaultWarehouseId");
        this.allowNegativeStock = allowNegativeStock;
        this.defaultMinimumStock = minimum(defaultMinimumStock);
        this.alertsEnabled = alertsEnabled;
    }

    public UUID getStoreId() {
        return storeId;
    }

    public UUID getDefaultWarehouseId() {
        return defaultWarehouseId;
    }

    public boolean isAllowNegativeStock() {
        return allowNegativeStock;
    }

    public BigDecimal getDefaultMinimumStock() {
        return defaultMinimumStock;
    }

    public boolean isAlertsEnabled() {
        return alertsEnabled;
    }

    static BigDecimal minimum(BigDecimal value) {
        Objects.requireNonNull(value, "minimumStock");
        if (value.signum() < 0) {
            throw new IllegalArgumentException("minimumStock no puede ser negativo");
        }
        if (value.stripTrailingZeros().scale() > 3) {
            throw new IllegalArgumentException("minimumStock admite un maximo de 3 decimales");
        }
        return value.setScale(3);
    }
}
