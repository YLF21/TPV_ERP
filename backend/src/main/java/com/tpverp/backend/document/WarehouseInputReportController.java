package com.tpverp.backend.document;

import com.tpverp.backend.inventory.WarehouseInputDocumentType;
import com.tpverp.backend.document.template.RenderedDocumentView;
import com.tpverp.backend.shared.api.PagedResult;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/document-reports/warehouse-inputs")
public class WarehouseInputReportController {

    private final WarehouseInputReportService service;

    public WarehouseInputReportController(WarehouseInputReportService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('GESTION_PRODUCTO','GESTION_ALMACEN','GESTION_CUENTAS')")
    public PagedResult<WarehouseInputReportView> list(
            @RequestParam WarehouseInputDocumentType type,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
            Authentication authentication) {
        return service.listPage(type, limit, cursor, dateFrom, dateTo, authentication);
    }

    @GetMapping("/{id}/print-document")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('GESTION_PRODUCTO','GESTION_ALMACEN','GESTION_CUENTAS')")
    public RenderedDocumentView printDocument(@PathVariable UUID id, Authentication authentication) {
        return service.printDocument(id, authentication);
    }
}
