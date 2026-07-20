package com.tpverp.backend.catalog;

import static com.tpverp.backend.security.application.CorePermissionBootstrap.GESTION_ALMACEN;
import static com.tpverp.backend.security.application.CorePermissionBootstrap.GESTION_PRODUCTO;
import static com.tpverp.backend.security.application.CorePermissionBootstrap.GESTION_VENTAS;
import static com.tpverp.backend.security.application.CorePermissionBootstrap.STOCK_READ;
import static com.tpverp.backend.security.application.CorePermissionBootstrap.VENTA;
import static com.tpverp.backend.security.application.CorePermissionBootstrap.WAREHOUSES_MANAGE;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/warehouses")
public class WarehouseController {

    private final CatalogService service;

    public WarehouseController(CatalogService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('" + STOCK_READ + "','" + GESTION_PRODUCTO + "','" + GESTION_ALMACEN + "','" + GESTION_VENTAS + "','" + VENTA + "')")
    public List<Warehouse> list() {
        return service.warehouses();
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('" + WAREHOUSES_MANAGE + "','" + GESTION_ALMACEN + "')")
    public Warehouse create(@Valid @RequestBody NameRequest request) {
        return service.createWarehouse(request.name());
    }

    @PutMapping("/{warehouseId}")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('" + WAREHOUSES_MANAGE + "','" + GESTION_ALMACEN + "')")
    public Warehouse rename(@PathVariable UUID warehouseId, @Valid @RequestBody NameRequest request) {
        return service.renameWarehouse(warehouseId, request.name());
    }

    @PatchMapping("/{warehouseId}/active")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('" + WAREHOUSES_MANAGE + "','" + GESTION_ALMACEN + "')")
    public Warehouse setActive(
            @PathVariable UUID warehouseId, @Valid @RequestBody TaxController.ActiveRequest request) {
        return service.setWarehouseActive(warehouseId, request.active());
    }

    @DeleteMapping("/{warehouseId}")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('" + WAREHOUSES_MANAGE + "')")
    public ResponseEntity<Void> delete(@PathVariable UUID warehouseId) {
        service.deleteWarehouse(warehouseId);
        return ResponseEntity.noContent().build();
    }

    public record NameRequest(@NotBlank String name) {
    }
}
