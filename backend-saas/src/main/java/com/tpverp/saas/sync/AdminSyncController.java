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

    @GetMapping("/events/page")
    public AdminSyncPage<AdminSyncEventView> eventsPage(
            @RequestParam(required = false) UUID companyId,
            @RequestParam(required = false) UUID storeId,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int size) {
        return service.page(null, companyId, storeId, cursor, size);
    }

    @GetMapping("/projection-status")
    public AdminSyncProjectionStatusView projectionStatus(
            @RequestParam(required = false) UUID companyId,
            @RequestParam(required = false) UUID storeId) {
        return service.projectionStatus(companyId, storeId);
    }

    @GetMapping("/sales")
    public List<AdminSyncEventView> sales(
            @RequestParam(required = false) UUID companyId,
            @RequestParam(required = false) UUID storeId) {
        return service.sales(companyId, storeId);
    }

    @GetMapping("/sales/page")
    public AdminSyncPage<AdminSyncEventView> salesPage(
            @RequestParam(required = false) UUID companyId,
            @RequestParam(required = false) UUID storeId,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int size) {
        return service.page("DOCUMENTO", companyId, storeId, cursor, size);
    }

    @GetMapping("/sales-summary")
    public AdminSalesSummaryView salesSummary(
            @RequestParam(required = false) UUID companyId,
            @RequestParam(required = false) UUID storeId) {
        return service.salesSummary(companyId, storeId);
    }

    @GetMapping("/stock-movements")
    public List<AdminSyncEventView> stockMovements(
            @RequestParam(required = false) UUID companyId,
            @RequestParam(required = false) UUID storeId) {
        return service.stockMovements(companyId, storeId);
    }

    @GetMapping("/stock-movements/page")
    public AdminSyncPage<AdminSyncEventView> stockMovementsPage(
            @RequestParam(required = false) UUID companyId,
            @RequestParam(required = false) UUID storeId,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int size) {
        return service.page("STOCK_MOVEMENT", companyId, storeId, cursor, size);
    }

    @GetMapping("/stock-current")
    public List<AdminStockSnapshotView> stockCurrent(
            @RequestParam(required = false) UUID companyId,
            @RequestParam(required = false) UUID storeId) {
        return service.stockCurrent(companyId, storeId);
    }

    @GetMapping("/stock-current/page")
    public AdminSyncPage<AdminStockSnapshotView> stockCurrentPage(
            @RequestParam(required = false) UUID companyId,
            @RequestParam(required = false) UUID storeId,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int size) {
        return service.stockPage(companyId, storeId, cursor, size);
    }

    @GetMapping("/cash-closures")
    public List<AdminSyncEventView> cashClosures(
            @RequestParam(required = false) UUID companyId,
            @RequestParam(required = false) UUID storeId) {
        return service.cashClosures(companyId, storeId);
    }

    @GetMapping("/cash-closures/page")
    public AdminSyncPage<AdminSyncEventView> cashClosuresPage(
            @RequestParam(required = false) UUID companyId,
            @RequestParam(required = false) UUID storeId,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int size) {
        return service.page("CIERRE_CAJA", companyId, storeId, cursor, size);
    }
}
