package com.tpverp.backend.document;

import static com.tpverp.backend.security.application.CorePermissionBootstrap.CASH_READ;
import static com.tpverp.backend.security.application.CorePermissionBootstrap.GESTION_CUENTAS;
import static com.tpverp.backend.security.application.CorePermissionBootstrap.GESTION_VENTAS;

import java.time.LocalDate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/commercial-reports")
public class CommercialReportController {

    private final DailyCommercialReportService reports;

    public CommercialReportController(DailyCommercialReportService reports) {
        this.reports = reports;
    }

    @GetMapping("/daily")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('"
            + GESTION_VENTAS + "','" + GESTION_CUENTAS + "','" + CASH_READ + "')")
    public DailyCommercialReportView daily(@RequestParam LocalDate date) {
        return reports.report(date);
    }
}
