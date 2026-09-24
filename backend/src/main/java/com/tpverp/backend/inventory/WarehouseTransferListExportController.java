package com.tpverp.backend.inventory;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/warehouse-transfers")
@PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('GESTION_ALMACEN', 'STOCK_TRANSFER')")
public class WarehouseTransferListExportController {
    private final WarehouseTransferListExportService service;

    public WarehouseTransferListExportController(WarehouseTransferListExportService service) { this.service = service; }

    @PostMapping("/report.{format:pdf|xlsx}")
    public ResponseEntity<byte[]> export(@PathVariable String format, @Valid @RequestBody Filters filters) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(format.equals("pdf") ? "application/pdf"
                        : "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("traspasos-almacen." + format).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(service.export(format, filters));
    }

    public record Filters(WarehouseTransferDocument.Status status, UUID sourceWarehouseId, UUID targetWarehouseId,
                          Instant from, Instant before, @Size(max = 120) String search, @Size(max = 16) String locale) {}
}
