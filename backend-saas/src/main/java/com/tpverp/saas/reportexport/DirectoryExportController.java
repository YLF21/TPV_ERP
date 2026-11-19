package com.tpverp.saas.reportexport;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/directories/export")
public class DirectoryExportController {
    private final DirectoryExportService service;

    DirectoryExportController(DirectoryExportService service) {
        this.service = service;
    }

    @PostMapping("/companies")
    public void companies(@RequestBody DirectoryExportService.CompanySelection selection,
            @RequestParam(defaultValue = "es") String language, HttpServletResponse response) throws IOException {
        try (var report = service.companies(selection, language)) {
            ReportExportController.download(report, "companies", "xlsx", response);
        }
    }

    @GetMapping("/stores")
    public void stores(@RequestParam(required = false) UUID companyId, @RequestParam(defaultValue = "") String q,
            @RequestParam(required = false) Boolean active, @RequestParam(required = false) String commercialConfiguration,
            @RequestParam(defaultValue = "companyName") String sortBy,
            @RequestParam(defaultValue = "ASC") String sortDirection, @RequestParam(defaultValue = "es") String language,
            HttpServletResponse response) throws IOException {
        try (var report = service.stores(companyId, q, active, commercialConfiguration, sortBy, sortDirection, language)) {
            ReportExportController.download(report, "stores", "xlsx", response);
        }
    }

    @GetMapping("/licenses")
    public void licenses(@RequestParam(required = false) UUID companyId, @RequestParam(required = false) UUID storeId,
            @RequestParam(defaultValue = "") String q,
            @RequestParam(required = false) String status, @RequestParam(required = false) Instant expiresBefore,
            @RequestParam(required = false) Boolean hasConnections, @RequestParam(required = false) String billingStatus,
            @RequestParam(defaultValue = "validUntil") String sortBy, @RequestParam(defaultValue = "ASC") String sortDirection,
            @RequestParam(defaultValue = "es") String language, HttpServletResponse response) throws IOException {
        try (var report = service.licenses(companyId, storeId, q, status, expiresBefore, hasConnections,
                billingStatus, sortBy, sortDirection, language)) {
            ReportExportController.download(report, "licenses", "xlsx", response);
        }
    }
}
