package com.tpverp.saas.movement;

import static com.tpverp.saas.movement.MovementReport.*;

import com.tpverp.saas.analytics.ReportStatistics.Comparison;
import com.tpverp.saas.analytics.ReportStatistics.Period;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/reports/movements")
public class MovementReportController {
    private final MovementReportService service;

    public MovementReportController(MovementReportService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<Summary> summary(
            @RequestParam LocalDate from,
            @RequestParam LocalDate to,
            @RequestParam(defaultValue = "NONE") Comparison comparison,
            @RequestParam(required = false) LocalDate comparisonFrom,
            @RequestParam(required = false) LocalDate comparisonTo,
            @RequestParam(required = false) UUID companyId,
            @RequestParam(required = false) UUID storeId,
            @RequestParam(required = false) String province,
            @RequestParam(required = false) UUID warehouseId,
            @RequestParam(required = false) UUID productId,
            @RequestParam(required = false) Type type,
            @RequestParam(defaultValue = "TYPE") GroupBy groupBy,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        Period period = Period.inclusive(from, to);
        Filter filter = new Filter(companyId, storeId, province, warehouseId, productId, type);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(
                        service.summary(
                                filter,
                                period,
                                period.comparison(comparison, comparisonFrom, comparisonTo),
                                groupBy,
                                page,
                                size));
    }

    @GetMapping("/ledger")
    public ResponseEntity<Ledger> ledger(
            @RequestParam LocalDate from,
            @RequestParam LocalDate to,
            @RequestParam(required = false) UUID companyId,
            @RequestParam(required = false) UUID storeId,
            @RequestParam(required = false) String province,
            @RequestParam(required = false) UUID warehouseId,
            @RequestParam(required = false) UUID productId,
            @RequestParam(required = false) Type type,
            @RequestParam(defaultValue = "OCCURRED_AT") Sort sort,
            @RequestParam(defaultValue = "DESC") Direction direction,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        Filter filter = new Filter(companyId, storeId, province, warehouseId, productId, type);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(
                        service.ledger(
                                filter, Period.inclusive(from, to), sort, direction, page, size));
    }
}
