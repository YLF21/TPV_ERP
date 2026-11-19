package com.tpverp.saas.analytics;

import static com.tpverp.saas.analytics.ReportStatistics.*;

import java.time.LocalDate;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import static org.springframework.format.annotation.DateTimeFormat.ISO.DATE;

@RestController
@RequestMapping("/api/v1/admin/reports/statistics")
public class ReportStatisticsController {
    private final ReportStatisticsService service;

    public ReportStatisticsController(ReportStatisticsService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<ReportStatistics> report(
            @RequestParam @DateTimeFormat(iso = DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DATE) LocalDate to,
            @RequestParam(defaultValue = "NONE") Comparison comparison,
            @RequestParam(required = false) @DateTimeFormat(iso = DATE) LocalDate comparisonFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DATE) LocalDate comparisonTo,
            @RequestParam(defaultValue = "DAY") GroupBy groupBy,
            @RequestParam(required = false) UUID companyId, @RequestParam(required = false) UUID storeId,
            @RequestParam(required = false) String province,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        var period = Period.inclusive(from, to);
        var result = service.report(period, period.comparison(comparison, comparisonFrom, comparisonTo),
                groupBy, companyId, storeId, province, page, size);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
    }

    @GetMapping("/options")
    public ResponseEntity<Options> options() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.options());
    }
}
