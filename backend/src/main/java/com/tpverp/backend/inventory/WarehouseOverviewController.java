package com.tpverp.backend.inventory;

import com.tpverp.backend.catalog.WarehouseRepository;
import com.tpverp.backend.organization.CurrentOrganization;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.transaction.annotation.Transactional;

@RestController
@RequestMapping("/api/v1/warehouses/overview")
public class WarehouseOverviewController {
    private final CurrentOrganization organization;
    private final WarehouseRepository warehouses;
    private final StockLevelRepository stocks;
    private final WarehouseStockSettingsRepository warehouseSettings;
    private final StockSettingsService stockSettings;

    public WarehouseOverviewController(CurrentOrganization organization, WarehouseRepository warehouses,
            StockLevelRepository stocks, WarehouseStockSettingsRepository warehouseSettings,
            StockSettingsService stockSettings) {
        this.organization = organization;
        this.warehouses = warehouses;
        this.stocks = stocks;
        this.warehouseSettings = warehouseSettings;
        this.stockSettings = stockSettings;
    }

    @GetMapping
    @Transactional
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('GESTION_ALMACEN','WAREHOUSES_MANAGE')")
    public List<WarehouseOverview> list() {
        UUID storeId = organization.currentStore().getId();
        var all = warehouses.findByStoreIdOrderByNombre(storeId);
        if (all.isEmpty()) return List.of();
        var ids = all.stream().map(value -> value.getId()).toList();
        Map<UUID, StockLevelRepository.WarehouseStockTotal> totals = stocks.totalsByWarehouseIds(ids)
                .stream().collect(Collectors.toMap(StockLevelRepository.WarehouseStockTotal::getWarehouseId,
                        Function.identity()));
        Map<UUID, WarehouseStockSettings> configured = warehouseSettings.findByStoreId(storeId)
                .stream().collect(Collectors.toMap(WarehouseStockSettings::getWarehouseId, Function.identity()));
        var defaults = stockSettings.settings();
        return all.stream().map(warehouse -> {
            var total = totals.get(warehouse.getId());
            var settings = configured.get(warehouse.getId());
            return new WarehouseOverview(warehouse.getId(), total == null ? 0 : total.getProductCount(),
                    total == null ? BigDecimal.ZERO : total.getTotalQuantity(),
                    settings == null ? defaults.allowNegativeStock() : settings.isAllowNegativeStock(),
                    settings == null ? defaults.defaultMinimumStock() : settings.getDefaultMinimumStock(),
                    settings == null ? defaults.alertsEnabled() : settings.isAlertsEnabled(),
                    settings == null || settings.isInheritsStoreSettings());
        }).toList();
    }

    public record WarehouseOverview(UUID warehouseId, long productCount, BigDecimal totalQuantity,
            boolean allowNegativeStock, BigDecimal defaultMinimumStock, boolean alertsEnabled,
            boolean inheritsStoreSettings) {}
}
