package com.tpverp.backend.excel;

import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/stock/products/{productId}/sales-history")
public class StockSalesHistoryExcelExportController {

    private static final MediaType XLSX = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    private final StockSalesHistoryExcelExportService service;

    public StockSalesHistoryExcelExportController(StockSalesHistoryExcelExportService service) {
        this.service = service;
    }

    @PostMapping(value = "/export", produces = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('STOCK_READ','GESTION_PRODUCTO','GESTION_VENTAS','VENTA')")
    public ResponseEntity<byte[]> export(
            @PathVariable UUID productId,
            @Valid @RequestBody StockSalesHistoryExportRequest request) {
        return ResponseEntity.ok()
                .contentType(XLSX)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("historial-ventas-producto.xlsx")
                        .build().toString())
                .body(service.export(productId, request));
    }
}
