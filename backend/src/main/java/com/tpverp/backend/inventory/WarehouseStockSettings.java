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
@Table(name = "configuracion_stock_almacen")
public class WarehouseStockSettings {
    @Id @Column(name = "almacen_id") private UUID warehouseId;
    @Column(name = "tienda_id", nullable = false) private UUID storeId;
    @Column(name = "permitir_stock_negativo", nullable = false) private boolean allowNegativeStock;
    @Column(name = "stock_minimo_predeterminado", nullable = false, precision = 19, scale = 3)
    private BigDecimal defaultMinimumStock;
    @Column(name = "alertas_habilitadas", nullable = false) private boolean alertsEnabled;
    @Column(name = "hereda_configuracion_tienda", nullable = false) private boolean inheritsStoreSettings = true;
    @Version private long version;

    protected WarehouseStockSettings() {}

    public WarehouseStockSettings(UUID warehouseId, UUID storeId, boolean allowNegativeStock,
                                  BigDecimal defaultMinimumStock, boolean alertsEnabled) {
        this.warehouseId = Objects.requireNonNull(warehouseId);
        this.storeId = Objects.requireNonNull(storeId);
        update(allowNegativeStock, defaultMinimumStock, alertsEnabled);
        inheritsStoreSettings = true;
    }

    public void update(boolean allowNegativeStock, BigDecimal defaultMinimumStock, boolean alertsEnabled) {
        if (defaultMinimumStock == null || defaultMinimumStock.signum() < 0
                || defaultMinimumStock.stripTrailingZeros().scale() > 3) {
            throw new IllegalArgumentException("Stock mínimo inválido");
        }
        this.allowNegativeStock = allowNegativeStock;
        this.defaultMinimumStock = defaultMinimumStock.setScale(3);
        this.alertsEnabled = alertsEnabled;
        inheritsStoreSettings = false;
    }

    public void inherit(boolean allowNegativeStock, BigDecimal defaultMinimumStock, boolean alertsEnabled) {
        update(allowNegativeStock, defaultMinimumStock, alertsEnabled);
        inheritsStoreSettings = true;
    }

    public UUID getWarehouseId() { return warehouseId; }
    public UUID getStoreId() { return storeId; }
    public boolean isAllowNegativeStock() { return allowNegativeStock; }
    public BigDecimal getDefaultMinimumStock() { return defaultMinimumStock; }
    public boolean isAlertsEnabled() { return alertsEnabled; }
    public boolean isInheritsStoreSettings() { return inheritsStoreSettings; }
    public long getVersion() { return version; }
}
