package com.tpverp.saas.analytics;

import java.time.LocalDate;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import static org.springframework.format.annotation.DateTimeFormat.ISO.DATE;

@RestController
@RequestMapping("/api/v1/admin/reports/articles")
public class ArticleReportController {
    private final ArticleReportService service;
    public ArticleReportController(ArticleReportService service) { this.service = service; }

    @GetMapping
    public ResponseEntity<ArticleReport> report(
            @RequestParam @DateTimeFormat(iso=DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso=DATE) LocalDate to,
            @RequestParam(defaultValue="NONE") ReportStatistics.Comparison comparison,
            @RequestParam(required=false) @DateTimeFormat(iso=DATE) LocalDate comparisonFrom,
            @RequestParam(required=false) @DateTimeFormat(iso=DATE) LocalDate comparisonTo,
            @RequestParam(required=false) UUID companyId, @RequestParam(required=false) UUID storeId,
            @RequestParam(required=false) String province, @RequestParam(required=false) String search,
            @RequestParam(defaultValue="AMOUNT") ArticleReport.Sort sort,
            @RequestParam(required=false) ArticleReport.Direction direction,
            @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="25") int size) {
        var period = ReportStatistics.Period.inclusive(from, to);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.report(period,
                period.comparison(comparison, comparisonFrom, comparisonTo),companyId,storeId,province,search,sort,direction,page,size));
    }
}
