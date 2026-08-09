package com.tpverp.backend.excel;

import static com.tpverp.backend.security.application.CorePermissionBootstrap.GESTION_ALMACEN;

import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/excel")
public class WarehouseDocumentExcelExportController {

    private static final MediaType XLSX = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final WarehouseDocumentExcelExportService service;

    public WarehouseDocumentExcelExportController(WarehouseDocumentExcelExportService service) {
        this.service = service;
    }

    @GetMapping("/warehouse-inputs/{documentId}/export")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('" + GESTION_ALMACEN + "')")
    public ResponseEntity<byte[]> exportInput(@PathVariable UUID documentId) {
        return file(service.exportInput(documentId), "entrada-almacen.xlsx");
    }

    @GetMapping("/warehouse-outputs/{documentId}/export")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('" + GESTION_ALMACEN + "')")
    public ResponseEntity<byte[]> exportOutput(@PathVariable UUID documentId) {
        return file(service.exportOutput(documentId), "salida-almacen.xlsx");
    }

    private static ResponseEntity<byte[]> file(byte[] content, String filename) {
        return ResponseEntity.ok()
                .contentType(XLSX)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(content);
    }
}
