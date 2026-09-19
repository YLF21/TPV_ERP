package com.tpverp.backend.inventory;

import static com.tpverp.backend.inventory.SaasProductSalesHistoryApi.*;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/stock/products/{productId}/sales-history/saas")
@PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('STOCK_READ','GESTION_PRODUCTO','GESTION_VENTAS','VENTA')")
public class SaasProductSalesHistoryController {
    private final SaasProductSalesHistoryService service;
    public SaasProductSalesHistoryController(SaasProductSalesHistoryService service) { this.service = service; }
    @GetMapping
    public ResponseEntity<String> page(@PathVariable UUID productId,
            @RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) String status, @RequestParam(required = false) List<UUID> storeIds,
            @RequestParam(required = false) String sortBy, @RequestParam(required = false) String sortDirection,
            @RequestParam(defaultValue = "100") int size, @RequestParam(required = false) String cursor) {
        // The client validates a Jackson 2 tree; serialize it there instead of exposing its bean getters to MVC's Jackson 3.
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.APPLICATION_JSON)
                .body(service.page(productId,
                        new Filters(from, to, status, storeIds, sortBy, sortDirection), size, cursor).toString());
    }
    @PostMapping("/export")
    public ResponseEntity<byte[]> excel(@PathVariable UUID productId, @Valid @RequestBody ExportRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=historial-ventas-saas.xlsx")
                .body(service.excel(productId, request));
    }
    @PostMapping("/render")
    public ResponseEntity<PdfResponse> pdf(@PathVariable UUID productId, @Valid @RequestBody ExportRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.pdf(productId, request));
    }
    @ExceptionHandler(SaasProductSalesHistoryException.class)
    public ResponseEntity<ProblemDetail> failure(SaasProductSalesHistoryException error) {
        var problem = ProblemDetail.forStatusAndDetail(error.status(), error.getMessage());
        problem.setProperty("code", error.getMessage());
        return ResponseEntity.status(error.status()).cacheControl(CacheControl.noStore()).body(problem);
    }
}
