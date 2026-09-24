package com.tpverp.backend.inventory;

import com.tpverp.backend.catalog.ProductRepository;
import com.tpverp.backend.catalog.Warehouse;
import com.tpverp.backend.catalog.WarehouseRepository;
import com.tpverp.backend.organization.CurrentOrganization;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StockSettingsService {

    private final CurrentOrganization organization;
    private final StockSettingsRepository settings;
    private final StockMinimumRepository minimums;
    private final ProductRepository products;
    private final WarehouseRepository warehouses;
    private WarehouseStockSettingsRepository warehouseSettings;

    public StockSettingsService(
            CurrentOrganization organization,
            StockSettingsRepository settings,
            StockMinimumRepository minimums,
            ProductRepository products,
            WarehouseRepository warehouses) {
        this.organization = organization;
        this.settings = settings;
        this.minimums = minimums;
        this.products = products;
        this.warehouses = warehouses;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setWarehouseSettings(WarehouseStockSettingsRepository warehouseSettings) {
        this.warehouseSettings = warehouseSettings;
    }

    @Transactional
    public WarehouseStockSettings warehouseSettings(UUID warehouseId) {
        var storeId = organization.currentStore().getId();
        warehouse(warehouseId, storeId);
        if (warehouseSettings == null) throw new IllegalStateException("Configuración de almacén no disponible");
        return warehouseSettings.findByWarehouseIdAndStoreId(warehouseId, storeId)
                .orElseGet(() -> {
                    var inherited = settingsFor(storeId);
                    return warehouseSettings.save(new WarehouseStockSettings(warehouseId, storeId,
                            inherited.isAllowNegativeStock(), inherited.getDefaultMinimumStock(),
                            inherited.isAlertsEnabled()));
                });
    }

    @Transactional
    public WarehouseStockSettings updateWarehouseSettings(UUID warehouseId, WarehouseStockSettingsCommand command) {
        Objects.requireNonNull(command);
        var current = warehouseSettings(warehouseId);
        if (!warehouse(warehouseId, current.getStoreId()).isActive()) {
            throw new IllegalStateException("El almacén no está activo");
        }
        current.update(command.allowNegativeStock(), command.defaultMinimumStock(), command.alertsEnabled());
        return warehouseSettings.save(current);
    }

    @Transactional
    public WarehouseStockSettings resetWarehouseSettings(UUID warehouseId) {
        var current = warehouseSettings(warehouseId);
        var defaults = settingsFor(current.getStoreId());
        current.inherit(defaults.isAllowNegativeStock(), defaults.getDefaultMinimumStock(), defaults.isAlertsEnabled());
        return warehouseSettings.save(current);
    }

    @Transactional
    public StockSettingsView applyWarehouseSettingsToAll(WarehouseStockSettingsCommand command) {
        Objects.requireNonNull(command, "command");
        var storeId = organization.currentStore().getId();
        var defaults = settingsFor(storeId);
        defaults.update(defaults.getDefaultWarehouseId(), command.allowNegativeStock(),
                command.defaultMinimumStock(), command.alertsEnabled());
        settings.save(defaults);
        var configured = warehouseSettings.findByStoreId(storeId).stream()
                .collect(java.util.stream.Collectors.toMap(WarehouseStockSettings::getWarehouseId, value -> value));
        var all = warehouses.findByStoreIdOrderByNombre(storeId).stream().map(warehouse -> {
            var value = configured.getOrDefault(warehouse.getId(), new WarehouseStockSettings(
                    warehouse.getId(), storeId, command.allowNegativeStock(),
                    command.defaultMinimumStock(), command.alertsEnabled()));
            value.inherit(command.allowNegativeStock(), command.defaultMinimumStock(), command.alertsEnabled());
            return value;
        }).toList();
        warehouseSettings.saveAll(all);
        return view(defaults);
    }

    @Transactional(readOnly = true)
    public boolean allowsNegativeStock(UUID warehouseId, UUID storeId) {
        if (warehouseSettings != null) {
            var configured = warehouseSettings.findByWarehouseIdAndStoreId(warehouseId, storeId);
            if (configured.isPresent()) return configured.get().isAllowNegativeStock();
        }
        return settings.findById(storeId).map(StockSettings::isAllowNegativeStock).orElse(true);
    }

    @Transactional
    public StockSettingsView settings() {
        var storeId = organization.currentStore().getId();
        return view(settingsFor(storeId));
    }

    @Transactional
    public StockSettingsView updateSettings(StockSettingsCommand command) {
        Objects.requireNonNull(command, "command");
        var storeId = organization.currentStore().getId();
        var warehouse = warehouse(command.defaultWarehouseId(), storeId);
        if (!warehouse.isActive()) {
            throw new IllegalArgumentException("message.warehouse.not_available_for_store");
        }
        var current = settings.findById(storeId)
                .orElseGet(() -> new StockSettings(storeId, warehouse.getId()));
        current.update(
                warehouse.getId(),
                Objects.requireNonNull(command.allowNegativeStock(), "allowNegativeStock"),
                command.defaultMinimumStock(),
                Objects.requireNonNull(command.alertsEnabled(), "alertsEnabled"));
        var saved = settings.save(current);
        if (warehouseSettings != null) {
            var inherited = warehouseSettings.findByStoreIdAndInheritsStoreSettingsTrue(storeId);
            inherited.forEach(value -> value.inherit(saved.isAllowNegativeStock(),
                    saved.getDefaultMinimumStock(), saved.isAlertsEnabled()));
            warehouseSettings.saveAll(inherited);
        }
        return view(saved);
    }

    @Transactional
    public StockSettingsView updateDefaultWarehouse(UUID warehouseId) {
        var storeId = organization.currentStore().getId();
        var selected = warehouse(warehouseId, storeId);
        if (!selected.isActive()) throw new IllegalArgumentException("message.warehouse.not_available_for_store");
        var current = settingsFor(storeId);
        current.update(selected.getId(), current.isAllowNegativeStock(),
                current.getDefaultMinimumStock(), current.isAlertsEnabled());
        return view(settings.save(current));
    }

    @Transactional
    public StockSettingsView updateInactiveProductSales(InactiveProductSalesCommand command) {
        Objects.requireNonNull(command, "command");
        var storeId = organization.currentStore().getId();
        var current = settingsFor(storeId);
        current.setAllowInactiveProductSales(Objects.requireNonNull(
                command.allowInactiveProductSales(), "allowInactiveProductSales"));
        return view(settings.save(current));
    }

    @Transactional(readOnly = true)
    public boolean allowsInactiveProductSales(UUID storeId) {
        return settings.findById(Objects.requireNonNull(storeId, "storeId"))
                .map(StockSettings::isAllowInactiveProductSales)
                .orElse(false);
    }

    @Transactional
    public StockMinimumView minimum(UUID productId, UUID warehouseId) {
        var storeId = organization.currentStore().getId();
        validateReferences(productId, warehouseId, storeId);
        return minimums.findByStoreIdAndProductIdAndWarehouseId(storeId, productId, warehouseId)
                .map(value -> minimumView(value, true))
                .orElseGet(() -> new StockMinimumView(
                        productId,
                        warehouseId,
                        warehouseSettings == null
                                ? settingsFor(storeId).getDefaultMinimumStock()
                                : warehouseSettings(warehouseId).getDefaultMinimumStock(),
                        false));
    }

    @Transactional
    public StockMinimumView updateMinimum(
            UUID productId, UUID warehouseId, StockMinimumCommand command) {
        Objects.requireNonNull(command, "command");
        var storeId = organization.currentStore().getId();
        validateReferences(productId, warehouseId, storeId);
        var minimum = minimums.findByStoreIdAndProductIdAndWarehouseId(
                        storeId, productId, warehouseId)
                .orElseGet(() -> new StockMinimum(
                        storeId, productId, warehouseId, command.minimumStock()));
        minimum.update(command.minimumStock());
        return minimumView(minimums.save(minimum), true);
    }

    @Transactional
    public void deleteMinimum(UUID productId, UUID warehouseId) {
        var storeId = organization.currentStore().getId();
        validateReferences(productId, warehouseId, storeId);
        minimums.findByStoreIdAndProductIdAndWarehouseId(storeId, productId, warehouseId)
                .ifPresent(minimums::delete);
    }

    private StockSettings settingsFor(UUID storeId) {
        return settings.findById(storeId).orElseGet(() -> settings.save(
                new StockSettings(storeId, defaultWarehouse(storeId).getId())));
    }

    private Warehouse defaultWarehouse(UUID storeId) {
        return warehouses.findByStoreIdAndPredeterminadoTrue(storeId)
                .orElseThrow(() -> new IllegalStateException("message.warehouse.default_not_found"));
    }

    private void validateReferences(UUID productId, UUID warehouseId, UUID storeId) {
        var product = products.findById(Objects.requireNonNull(productId, "productId"))
                .orElseThrow(() -> new IllegalArgumentException("message.product.not_found"));
        if (!product.getStoreId().equals(storeId)) {
            throw new IllegalArgumentException("message.product.not_available_for_store");
        }
        warehouse(warehouseId, storeId);
    }

    private Warehouse warehouse(UUID warehouseId, UUID storeId) {
        var warehouse = warehouses.findById(Objects.requireNonNull(warehouseId, "warehouseId"))
                .orElseThrow(() -> new IllegalArgumentException("message.warehouse.not_found"));
        if (!warehouse.getStoreId().equals(storeId)) {
            throw new IllegalArgumentException("message.warehouse.not_available_for_store");
        }
        return warehouse;
    }

    private static StockSettingsView view(StockSettings value) {
        return new StockSettingsView(
                value.getDefaultWarehouseId(),
                value.isAllowNegativeStock(),
                value.getDefaultMinimumStock(),
                value.isAlertsEnabled(),
                value.isAllowInactiveProductSales());
    }

    private static StockMinimumView minimumView(StockMinimum value, boolean overridden) {
        return new StockMinimumView(
                value.getProductId(),
                value.getWarehouseId(),
                value.getMinimumStock(),
                overridden);
    }
}
