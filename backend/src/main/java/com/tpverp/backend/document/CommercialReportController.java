package com.tpverp.backend.document;

import static com.tpverp.backend.security.application.CorePermissionBootstrap.CASH_READ;
import static com.tpverp.backend.security.application.CorePermissionBootstrap.GESTION_CUENTAS;
import static com.tpverp.backend.security.application.CorePermissionBootstrap.GESTION_VENTAS;

import com.tpverp.backend.cash.CashPermissionService;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/commercial-reports")
public class CommercialReportController {

    private final DailyCommercialReportService reports;
    private final CashPermissionService cashPermissions;

    public CommercialReportController(
            DailyCommercialReportService reports,
            CashPermissionService cashPermissions) {
        this.reports = reports;
        this.cashPermissions = cashPermissions;
    }

    @GetMapping("/daily")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('"
            + GESTION_VENTAS + "','" + GESTION_CUENTAS + "','" + CASH_READ + "')")
    public DailyCommercialReportView daily(
            @RequestParam(required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate date,
            @RequestParam(required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate dateFrom,
            @RequestParam(required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate dateTo,
            Authentication authentication) {
        var from = dateFrom != null ? dateFrom : date;
        var to = dateTo != null ? dateTo : from;
        return reports.report(
                from,
                to,
                cashPermissions.canSeeExpectedTotals(authentication));
    }
}
