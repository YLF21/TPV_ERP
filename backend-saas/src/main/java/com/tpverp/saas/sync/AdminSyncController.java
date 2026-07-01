package com.tpverp.saas.sync;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/sync")
public class AdminSyncController {

    private final AdminSyncQueryService service;

    public AdminSyncController(AdminSyncQueryService service) {
        this.service = service;
    }

    @GetMapping("/events")
    public List<AdminSyncEventView> events() {
        return service.events();
    }

    @GetMapping("/sales")
    public List<AdminSyncEventView> sales() {
        return service.sales();
    }

    @GetMapping("/stock-movements")
    public List<AdminSyncEventView> stockMovements() {
        return service.stockMovements();
    }

    @GetMapping("/cash-closures")
    public List<AdminSyncEventView> cashClosures() {
        return service.cashClosures();
    }
}
