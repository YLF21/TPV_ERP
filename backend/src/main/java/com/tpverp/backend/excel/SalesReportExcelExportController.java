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
    private final SalesReportExcelExportService service;

    public SalesReportExcelExportController(SalesReportExcelExportService service) {
        this.service = service;
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
}
