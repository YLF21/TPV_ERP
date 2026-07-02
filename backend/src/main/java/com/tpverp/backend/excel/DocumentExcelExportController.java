package com.tpverp.backend.excel;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/excel/documents")
public class DocumentExcelExportController {

    private static final MediaType XLSX = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final DocumentExcelExportService service;

    public DocumentExcelExportController(DocumentExcelExportService service) {
        this.service = service;
    }

    @GetMapping("/{documentId}/export")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('GESTION_VENTAS','VENTA')")
    public ResponseEntity<byte[]> export(@PathVariable UUID documentId) {
        return file(service.export(documentId), "documento.xlsx");
    }

    @PostMapping("/export")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('GESTION_VENTAS','VENTA')")
    public ResponseEntity<byte[]> exportBatch(@RequestBody ExportRequest request) {
        return file(service.export(request.documentIds()), "documentos.xlsx");
    }

    private static ResponseEntity<byte[]> file(byte[] content, String filename) {
        return ResponseEntity.ok()
                .contentType(XLSX)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(content);
    }

    public record ExportRequest(@NotEmpty List<UUID> documentIds) {
    }
}
