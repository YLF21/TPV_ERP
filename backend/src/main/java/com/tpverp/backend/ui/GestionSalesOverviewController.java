package com.tpverp.backend.ui;

import java.time.LocalDate;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/gestion/dashboard/data")
public class GestionSalesOverviewController {

    private final GestionSalesOverviewService service;

    public GestionSalesOverviewController(GestionSalesOverviewService service) {
        this.service = service;
    }

    @GetMapping("/sales-overview")
    @PreAuthorize("(hasRole('ADMIN') or hasAuthority('APP_GESTION_ACCESS'))"
            + " and (hasRole('ADMIN') or hasAuthority('GESTION_VENTAS'))")
    public GestionSalesOverviewService.Overview overview(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) UUID warehouseId) {
        return service.overview(from, to, warehouseId);
    }

    @GetMapping("/sales-hourly")
    @PreAuthorize("(hasRole('ADMIN') or hasAuthority('APP_GESTION_ACCESS'))"
            + " and (hasRole('ADMIN') or hasAuthority('GESTION_VENTAS'))")
    public GestionSalesOverviewService.HourlyComparison hourly(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate day,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate comparisonDay,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate comparisonFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate comparisonTo,
            @RequestParam(required = false) UUID warehouseId) {
        return service.hourlyComparison(day, comparisonDay, from, to, comparisonFrom, comparisonTo, warehouseId);
    }
}
