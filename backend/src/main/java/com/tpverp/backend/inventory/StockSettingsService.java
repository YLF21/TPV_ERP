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
        return view(settings.save(current));
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
                        settingsFor(storeId).getDefaultMinimumStock(),
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
                value.isAlertsEnabled());
    }

    private static StockMinimumView minimumView(StockMinimum value, boolean overridden) {
        return new StockMinimumView(
                value.getProductId(),
                value.getWarehouseId(),
                value.getMinimumStock(),
                overridden);
    }
}
