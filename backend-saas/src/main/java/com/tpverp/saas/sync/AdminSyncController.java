package com.tpverp.saas.sync;

import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/sync")
public class AdminSyncController {

    private final AdminSyncQueryService service;

    public AdminSyncController(AdminSyncQueryService service) {
        this.service = service;
    }

    @GetMapping("/events")
    public List<AdminSyncEventView> events(
            @RequestParam(required = false) UUID companyId,
            @RequestParam(required = false) UUID storeId) {
        return service.events(companyId, storeId);
    }

    @GetMapping("/sales")
    public List<AdminSyncEventView> sales(
            @RequestParam(required = false) UUID companyId,
            @RequestParam(required = false) UUID storeId) {
        return service.sales(companyId, storeId);
    }

    @GetMapping("/stock-movements")
    public List<AdminSyncEventView> stockMovements(
            @RequestParam(required = false) UUID companyId,
            @RequestParam(required = false) UUID storeId) {
        return service.stockMovements(companyId, storeId);
    }

    @GetMapping("/stock-current")
    public List<AdminStockSnapshotView> stockCurrent(
            @RequestParam(required = false) UUID companyId,
            @RequestParam(required = false) UUID storeId) {
        return service.stockCurrent(companyId, storeId);
    }

    @GetMapping("/cash-closures")
    public List<AdminSyncEventView> cashClosures(
            @RequestParam(required = false) UUID companyId,
            @RequestParam(required = false) UUID storeId) {
        return service.cashClosures(companyId, storeId);
    }
}
