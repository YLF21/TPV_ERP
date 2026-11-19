package com.tpverp.saas.inventory;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/reports/inventory/comparison")
public class InventoryComparisonController {
    private final InventoryComparisonService service;

    public InventoryComparisonController(InventoryComparisonService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<InventoryComparison> compare(
            @RequestParam UUID companyId,
            @RequestParam UUID storeId,
            @RequestParam UUID previousSnapshotId,
            @RequestParam UUID currentSnapshotId,
            @RequestParam(required = false) UUID warehouseId,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "false") boolean changedOnly,
            @RequestParam(defaultValue = "PRODUCT") InventoryComparison.Sort sort,
            @RequestParam(defaultValue = "ASC") InventoryComparison.Direction direction,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(
                        service.compare(
                                companyId,
                                storeId,
                                previousSnapshotId,
                                currentSnapshotId,
                                warehouseId,
                                search,
                                changedOnly,
                                sort,
                                direction,
                                page,
                                size));
    }
}
