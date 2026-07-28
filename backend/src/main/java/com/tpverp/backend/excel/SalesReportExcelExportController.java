package com.tpverp.backend.excel;

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
                        .filename("informe.xlsx")
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
                        .filename("informe.pdf")
                        .build().toString())
                .body(pdfService.export(request, authentication));
    }
}
