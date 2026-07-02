package com.tpverp.backend.inventory;

import static com.tpverp.backend.security.application.CorePermissionBootstrap.GESTION_PRODUCTO;
import static com.tpverp.backend.security.application.CorePermissionBootstrap.STOCK_ADJUST;
import static com.tpverp.backend.security.application.CorePermissionBootstrap.STOCK_READ;
import static com.tpverp.backend.security.application.CorePermissionBootstrap.STOCK_TRANSFER;
import static com.tpverp.backend.security.application.CorePermissionBootstrap.VENTA;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/stock")
public class StockController {

    private final InventoryService service;
    private final StockSnapshotRebuildService snapshotRebuildService;

    public StockController(
            InventoryService service,
            StockSnapshotRebuildService snapshotRebuildService) {
        this.service = service;
        this.snapshotRebuildService = snapshotRebuildService;
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('" + STOCK_READ + "','" + GESTION_PRODUCTO + "','" + VENTA + "')")
    public List<InventoryService.StockItem> list(
            @RequestParam(required = false) UUID productId,
            @RequestParam(required = false) UUID warehouseId) {
        return service.stock(productId, warehouseId);
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
