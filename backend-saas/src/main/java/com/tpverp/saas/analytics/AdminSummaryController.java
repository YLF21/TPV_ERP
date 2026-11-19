package com.tpverp.saas.analytics;

import java.time.LocalDate;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import static org.springframework.format.annotation.DateTimeFormat.ISO.DATE;

@RestController
@RequestMapping("/api/v1/admin/summary")
public class AdminSummaryController {
    private final AdminSummaryService service;
    private final ReportStatisticsService statistics;

    public AdminSummaryController(AdminSummaryService service, ReportStatisticsService statistics) {
        this.service = service;
        this.statistics = statistics;
    }

    @GetMapping
    public ResponseEntity<AdminSummary> summary(
            @RequestParam(required=false) @DateTimeFormat(iso=DATE) LocalDate from,
            @RequestParam(required=false) @DateTimeFormat(iso=DATE) LocalDate to,
            @RequestParam(required=false) UUID companyId, @RequestParam(required=false) UUID storeId,
            @RequestParam(required=false) String province) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.summary(from, to, companyId, storeId, province));
    }

    @GetMapping("/options")
    public ResponseEntity<ReportStatistics.Options> options() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(statistics.options());
    }
}
