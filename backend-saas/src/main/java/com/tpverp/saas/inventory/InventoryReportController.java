package com.tpverp.saas.inventory;

import static com.tpverp.saas.inventory.InventoryReport.*;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/reports/inventory")
public class InventoryReportController {
    private final InventoryReportService reports;

    public InventoryReportController(InventoryReportService reports) {
        this.reports = reports;
    }

    @GetMapping("/provinces")
    public ResponseEntity<java.util.List<Province>> provinces(
            @RequestParam(required = false) UUID companyId,
            @RequestParam(required = false) UUID storeId,
            @RequestParam(required = false) String province) {
        return view(reports.provinces(companyId, storeId, province));
    }

    @GetMapping("/overview")
    public ResponseEntity<Overview> overview(
            @RequestParam(required = false) UUID companyId,
            @RequestParam(required = false) UUID storeId,
            @RequestParam(required = false) String province,
            @RequestParam(defaultValue = "COMPANY") OverviewSort sort,
            @RequestParam(defaultValue = "ASC") Direction direction,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return view(reports.overview(companyId, storeId, province, sort, direction, page, size));
    }

    @GetMapping("/history")
    public ResponseEntity<Page<Snapshot>> history(
            @RequestParam UUID companyId,
            @RequestParam UUID storeId,
            @RequestParam(defaultValue = "CAPTURED_AT") HistorySort sort,
            @RequestParam(defaultValue = "DESC") Direction direction,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return view(reports.history(companyId, storeId, sort, direction, page, size));
    }

    @GetMapping("/products")
    public ResponseEntity<Detail<Product>> products(
            @RequestParam UUID companyId,
            @RequestParam UUID storeId,
            @RequestParam(required = false) UUID snapshotId,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Boolean active,
            @RequestParam(defaultValue = "PRODUCT") ProductSort sort,
            @RequestParam(defaultValue = "ASC") Direction direction,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return view(
                reports.products(
                        companyId,
                        storeId,
                        snapshotId,
                        search,
                        active,
                        sort,
                        direction,
                        page,
                        size));
    }

    @GetMapping("/warehouses")
    public ResponseEntity<Detail<Warehouse>> warehouses(
            @RequestParam UUID companyId,
            @RequestParam UUID storeId,
            @RequestParam(required = false) UUID snapshotId,
            @RequestParam(defaultValue = "WAREHOUSE") WarehouseSort sort,
            @RequestParam(defaultValue = "ASC") Direction direction,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return view(
                reports.warehouses(companyId, storeId, snapshotId, sort, direction, page, size));
    }

    @GetMapping("/balances")
    public ResponseEntity<Detail<Balance>> balances(
            @RequestParam UUID companyId,
            @RequestParam UUID storeId,
            @RequestParam(required = false) UUID snapshotId,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Boolean active,
            @RequestParam(required = false) UUID productId,
            @RequestParam(required = false) UUID warehouseId,
            @RequestParam(defaultValue = "PRODUCT") BalanceSort sort,
            @RequestParam(defaultValue = "ASC") Direction direction,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return view(
                reports.balances(
                        companyId,
                        storeId,
                        snapshotId,
                        search,
                        active,
                        productId,
                        warehouseId,
                        sort,
                        direction,
                        page,
                        size));
    }

    private static <T> ResponseEntity<T> view(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
