package com.tpverp.saas.analytics;

import java.time.LocalDate;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import static org.springframework.format.annotation.DateTimeFormat.ISO.DATE;

@RestController
@RequestMapping("/api/v1/admin/reports/documents")
public class DocumentReportController {
    private final DocumentReportService service;
    public DocumentReportController(DocumentReportService service) { this.service = service; }

    @GetMapping
    public ResponseEntity<DocumentReport> report(
            @RequestParam @DateTimeFormat(iso=DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso=DATE) LocalDate to,
            @RequestParam(required=false) UUID companyId, @RequestParam(required=false) UUID storeId,
            @RequestParam(required=false) String province, @RequestParam(required=false) UUID productId,
            @RequestParam(required=false) String productCode, @RequestParam(required=false) String currency,
            @RequestParam(defaultValue="DATE") DocumentReport.Sort sort,
            @RequestParam(required=false) DocumentReport.Direction direction,
            @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="25") int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.report(
                ReportStatistics.Period.inclusive(from,to), companyId, storeId, province, productId, productCode,
                currency, sort, direction, page, size));
    }
}
