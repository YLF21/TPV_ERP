package com.tpverp.backend.inventory;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
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

/** Exports the rows visible in an unsaved input or output document without persisting them. */
@RestController
@RequestMapping("/api/v1/warehouse-draft-export")
public class WarehouseDraftExportController {
    private static final MediaType XLSX = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    private final WarehouseDraftExportService service;

    public WarehouseDraftExportController(WarehouseDraftExportService service) {
        this.service = service;
    }

    @PostMapping("/input.xlsx")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('GESTION_PRODUCTO','GESTION_ALMACEN','GESTION_CUENTAS')")
    public ResponseEntity<byte[]> input(@Valid @RequestBody Request request, Authentication authentication) {
        return file(service.export(request, true, authentication), "entrada-almacen.xlsx");
    }

    @PostMapping("/output.xlsx")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('GESTION_ALMACEN')")
    public ResponseEntity<byte[]> output(@Valid @RequestBody Request request) {
        return file(service.export(request, false, null), "salida-almacen.xlsx");
    }

    private static ResponseEntity<byte[]> file(byte[] content, String filename) {
        return ResponseEntity.ok().contentType(XLSX)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(filename).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(content);
    }

    public record Request(@Size(max = 16) String locale,
                          WarehouseInputDocumentType inputDocumentType,
                          @Size(max = 64) String documentType,
                          @Size(max = 64) String number,
                          @Size(max = 64) String status,
                          LocalDate date,
                          @Size(max = 255) String warehouse,
                          @NotNull @Size(max = 5000) List<@NotNull @Valid Line> lines) { }

    public record Line(@NotNull Integer row,
                       @Size(max = 32767) String code,
                       @Size(max = 32767) String name,
                       BigDecimal quantity,
                       @Size(max = 32767) String validationStatus) { }
}
