package com.tpverp.backend.inventory;

import static com.tpverp.backend.security.application.CorePermissionBootstrap.GESTION_PRODUCTO;
import static com.tpverp.backend.security.application.CorePermissionBootstrap.STOCK_ADJUST;
import static com.tpverp.backend.security.application.CorePermissionBootstrap.STOCK_READ;
import static com.tpverp.backend.security.application.CorePermissionBootstrap.STOCK_TRANSFER;
import static com.tpverp.backend.security.application.CorePermissionBootstrap.VENTA;
import static com.tpverp.backend.security.application.CorePermissionBootstrap.WAREHOUSES_MANAGE;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/stock")
public class StockController {

    private final InventoryService service;
    private final StockSnapshotRebuildService snapshotRebuildService;
    private final StockTopSalesService topSalesService;
    private final StockSalesHistoryService salesHistoryService;
    private final StockSettingsService settingsService;

    public StockController(
            InventoryService service,
            StockSnapshotRebuildService snapshotRebuildService,
            StockTopSalesService topSalesService,
            StockSalesHistoryService salesHistoryService,
            StockSettingsService settingsService) {
        this.service = service;
        this.snapshotRebuildService = snapshotRebuildService;
        this.topSalesService = topSalesService;
        this.salesHistoryService = salesHistoryService;
        this.settingsService = settingsService;
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('" + STOCK_READ + "','" + GESTION_PRODUCTO + "','" + VENTA + "')")
    public List<InventoryService.StockItem> list(
            @RequestParam(required = false) UUID productId,
            @RequestParam(required = false) UUID warehouseId) {
        return service.stock(productId, warehouseId);
    }

    @GetMapping("/top-sales")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('" + STOCK_READ + "','" + GESTION_PRODUCTO + "','" + VENTA + "')")
    public List<StockTopSalesRow> topSales(
            @RequestParam(defaultValue = "week") String period,
            @RequestParam(required = false) LocalDate date,
            @RequestParam(required = false) LocalDate dateFrom,
            @RequestParam(required = false) LocalDate dateTo,
            @RequestParam(required = false) UUID warehouseId) {
        if (dateFrom != null || dateTo != null) {
            var selectedFrom = dateFrom == null ? dateTo : dateFrom;
            var selectedTo = dateTo == null ? selectedFrom : dateTo;
            return topSalesService.topSales(selectedFrom, selectedTo, warehouseId);
        }
        return topSalesService.topSales(
                StockTopSalesPeriod.fromCode(period),
                date == null ? LocalDate.now() : date,
                warehouseId);
    }

    @GetMapping("/products/{productId}/sales-history")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('" + STOCK_READ + "','" + GESTION_PRODUCTO + "')")
    public List<StockSalesHistoryRow> salesHistory(
            @PathVariable UUID productId,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to) {
        return salesHistoryService.history(productId, from, to);
    }

    @GetMapping("/settings")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('" + STOCK_READ + "','" + GESTION_PRODUCTO + "')")
    public StockSettingsView settings() {
        return settingsService.settings();
    }

    @PutMapping("/settings")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('" + WAREHOUSES_MANAGE + "','" + GESTION_PRODUCTO + "')")
    public StockSettingsView updateSettings(
            @Valid @RequestBody StockSettingsCommand command) {
        return settingsService.updateSettings(command);
    }

    @GetMapping("/minimums/{productId}/{warehouseId}")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('" + STOCK_READ + "','" + GESTION_PRODUCTO + "')")
    public StockMinimumView minimum(
            @PathVariable UUID productId, @PathVariable UUID warehouseId) {
        return settingsService.minimum(productId, warehouseId);
    }

    @PutMapping("/minimums/{productId}/{warehouseId}")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('" + WAREHOUSES_MANAGE + "','" + GESTION_PRODUCTO + "')")
    public StockMinimumView updateMinimum(
            @PathVariable UUID productId,
            @PathVariable UUID warehouseId,
            @Valid @RequestBody StockMinimumCommand command) {
        return settingsService.updateMinimum(productId, warehouseId, command);
    }

    @DeleteMapping("/minimums/{productId}/{warehouseId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('" + WAREHOUSES_MANAGE + "','" + GESTION_PRODUCTO + "')")
    public void deleteMinimum(
            @PathVariable UUID productId, @PathVariable UUID warehouseId) {
        settingsService.deleteMinimum(productId, warehouseId);
    }

    @GetMapping("/movements")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('" + STOCK_READ + "','" + GESTION_PRODUCTO + "','" + VENTA + "')")
    public List<StockMovement> movements(@RequestParam UUID productId) {
        return service.movements(productId);
    }

    @PostMapping("/adjustments")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('" + STOCK_ADJUST + "','" + GESTION_PRODUCTO + "')")
    public InventoryService.StockItem adjust(
            @Valid @RequestBody AdjustmentRequest request, Authentication authentication) {
        return service.adjust(
                request.productId(), request.warehouseId(), request.quantity(),
                request.reason(), authentication);
    }

    @PostMapping("/transfers")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('" + STOCK_TRANSFER + "','" + GESTION_PRODUCTO + "')")
    public InventoryService.TransferResult transfer(
            @Valid @RequestBody TransferRequest request, Authentication authentication) {
        return service.transfer(
                request.productId(), request.sourceWarehouseId(), request.targetWarehouseId(),
                request.quantity(), authentication);
    }

    @PostMapping("/snapshots/rebuild")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('" + STOCK_ADJUST + "','" + GESTION_PRODUCTO + "')")
    public StockSnapshotRebuildResult rebuildSnapshots() {
        return snapshotRebuildService.rebuild();
    }

    public record AdjustmentRequest(
            @NotNull UUID productId,
            @NotNull UUID warehouseId,
            @NotNull BigDecimal quantity,
            @NotBlank String reason) {
    }

    public record TransferRequest(
            @NotNull UUID productId,
            @NotNull UUID sourceWarehouseId,
            @NotNull UUID targetWarehouseId,
            @NotNull @Positive BigDecimal quantity) {
    }
}
