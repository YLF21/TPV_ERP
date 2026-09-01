package com.tpverp.backend.document;

import com.tpverp.backend.document.template.DocumentTemplateFormat;
import com.tpverp.backend.document.template.SalesActivityJasperRenderer;
import com.tpverp.backend.excel.SalesActivityExcelExportService;
import com.tpverp.backend.cash.CashPermissionService;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/sales-activity")
@PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('GESTION_VENTAS','GESTION_CUENTAS')")
public class SalesActivityReportController {

    private static final MediaType XLSX = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    private final SalesActivityReportService reports;
    private final SalesActivityExcelExportService excel;
    private final SalesActivityJasperRenderer jasper;
    private final CashPermissionService cashPermissions;

    public SalesActivityReportController(
            SalesActivityReportService reports,
            SalesActivityExcelExportService excel,
            SalesActivityJasperRenderer jasper) {
        this(reports, excel, jasper, null);
    }

    @Autowired
    public SalesActivityReportController(
            SalesActivityReportService reports,
            SalesActivityExcelExportService excel,
            SalesActivityJasperRenderer jasper,
            CashPermissionService cashPermissions) {
        this.reports = reports;
        this.excel = excel;
        this.jasper = jasper;
        this.cashPermissions = cashPermissions;
    }

    @GetMapping("/daily")
    public SalesDailySummaryView daily(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            Authentication authentication) {
        var canSeeExpected = cashPermissions != null
                && cashPermissions.canSeeExpectedTotals(authentication);
        return reports.daily(date, canSeeExpected);
    }

    /** Compatibility for direct callers; HTTP requests always use the secured overload. */
    public SalesDailySummaryView daily(LocalDate date) {
        return reports.daily(date);
    }

    @GetMapping("/documents")
    public SalesActivityDocumentPageView documents(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor) {
        return reports.documents(dateFrom, dateTo, limit, cursor);
    }

    @GetMapping("/documents/by-day")
    public SalesActivityDailyDocumentPageView dailyDocuments(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor) {
        return reports.dailyDocuments(dateFrom, dateTo, limit, cursor);
    }

    @GetMapping("/filter-options")
    public SalesActivityFilterOptionsView filterOptions() {
        return reports.filterOptions();
    }

    @GetMapping("/daily/excel")
    public ResponseEntity<byte[]> dailyExcel(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            Authentication authentication) {
        var summary = daily(date, authentication);
        return excel("resumen-ventas-" + date + ".xlsx", excel.daily(summary));
    }

    @GetMapping("/documents/excel")
    public ResponseEntity<byte[]> documentsExcel(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
            @RequestParam(defaultValue = "DOCUMENT") SalesActivityPrintGrouping grouping) {
        return excel("documentos-ventas-" + dateFrom + "-" + dateTo + ".xlsx",
                excel.documents(dateFrom, dateTo, grouping));
    }

    public ResponseEntity<byte[]> documentsExcel(LocalDate dateFrom, LocalDate dateTo) {
        return excel("documentos-ventas-" + dateFrom + "-" + dateTo + ".xlsx",
                excel.documents(dateFrom, dateTo));
    }

    @GetMapping("/daily/render")
    public SalesActivityRenderedReportView renderDaily(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(defaultValue = "A4") DocumentTemplateFormat format,
            Authentication authentication) {
        var rendered = jasper.renderDaily(daily(date, authentication), format);
        return SalesActivityRenderedReportView.from(
                rendered.pdf(), rendered.ticketRasterPng());
    }

    @GetMapping("/documents/render")
    public SalesActivityRenderedReportView renderDocuments(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
            @RequestParam(defaultValue = "DAY") SalesActivityPrintGrouping grouping,
            @RequestParam(defaultValue = "A4") DocumentTemplateFormat format) {
        var rendered = grouping == SalesActivityPrintGrouping.DAY
                ? jasper.renderDailyDocuments(
                        reports.allDailyDocuments(dateFrom, dateTo), dateFrom, dateTo, format)
                : jasper.renderDocuments(
                        reports.allDocuments(dateFrom, dateTo), dateFrom, dateTo, grouping, format);
        return SalesActivityRenderedReportView.from(
                rendered.pdf(), rendered.ticketRasterPng());
    }

    private static ResponseEntity<byte[]> excel(String filename, byte[] bytes) {
        return ResponseEntity.ok().contentType(XLSX)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(filename).build().toString())
                .body(bytes);
    }
}
