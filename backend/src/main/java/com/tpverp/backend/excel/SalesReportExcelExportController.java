package com.tpverp.backend.excel;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import jakarta.validation.Valid;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/sales-reports")
public class SalesReportExcelExportController {

    private static final MediaType XLSX = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    private static final MediaType PDF = MediaType.APPLICATION_PDF;
    private static final DateTimeFormatter EXPORT_DATE_FORMAT = DateTimeFormatter.ofPattern("ddMMyy");
    private final SalesReportExcelExportService service;
    private final SalesReportPdfExportService pdfService;

    public SalesReportExcelExportController(
            SalesReportExcelExportService service,
            SalesReportPdfExportService pdfService) {
        this.service = service;
        this.pdfService = pdfService;
    }

    @PostMapping(value = "/export", produces = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('GESTION_VENTAS','GESTION_PRODUCTO','GESTION_ALMACEN','GESTION_CUENTAS')")
    public ResponseEntity<byte[]> export(
            @Valid @RequestBody SalesReportExportRequest request,
            Authentication authentication) {
        return ResponseEntity.ok()
                .contentType(XLSX)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(exportFilename(request, "xlsx"))
                        .build().toString())
                .body(service.export(request, authentication));
    }

    @PostMapping(value = "/export-pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('GESTION_VENTAS','GESTION_PRODUCTO','GESTION_ALMACEN','GESTION_CUENTAS')")
    public ResponseEntity<byte[]> exportPdf(
            @Valid @RequestBody SalesReportExportRequest request,
            Authentication authentication) {
        return ResponseEntity.ok()
                .contentType(PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(exportFilename(request, "pdf"))
                        .build().toString())
                .body(pdfService.export(request, authentication));
    }

    private String exportFilename(SalesReportExportRequest request, String extension) {
        String stem = switch (request.reportKey()) {
            case "salesReport.tickets" -> "Tickets";
            case "salesReport.invoices" -> "Facturas";
            case "salesReport.deliveryNotes" -> "Albaranes";
            case "salesReport.inputInvoices" -> "Facturas_de_compra";
            case "salesReport.inputDeliveryNotes" -> "Albaranes_de_compra";
            default -> "";
        };
        if (stem.isEmpty()) {
            return "informe." + extension;
        }

        SalesReportExportRequest.Filters filters = request.filters();
        if (filters != null && filters.dateFrom() != null && filters.dateTo() != null) {
            try {
                LocalDate dateFrom = LocalDate.parse(filters.dateFrom());
                LocalDate dateTo = LocalDate.parse(filters.dateTo());
                stem += "_" + dateFrom.format(EXPORT_DATE_FORMAT) + "-" + dateTo.format(EXPORT_DATE_FORMAT);
            } catch (DateTimeParseException ignored) {
                // Filename generation must not take over date validation from the export service.
            }
        }
        return stem + "." + extension;
    }
}
