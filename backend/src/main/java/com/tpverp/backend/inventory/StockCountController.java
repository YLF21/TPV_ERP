package com.tpverp.backend.inventory;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/stock-counts")
@PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('GESTION_ALMACEN', 'STOCK_ADJUST')")
public class StockCountController {
    private final StockCountService service;
    private final StockCountExportService exports;
    public StockCountController(StockCountService service, StockCountExportService exports) {
        this.service = service;
        this.exports = exports;
    }

    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public StockCountView create(@Valid @RequestBody CreateRequest request, Authentication authentication) {
        return service.create(request.warehouseId(), request.notes(), authentication);
    }
    @GetMapping
    public List<StockCountSummary> list(@RequestParam(required = false) StockCountStatus status,
                                        @RequestParam(required = false) UUID warehouseId) {
        return service.list(status, warehouseId);
    }
    @GetMapping("/{id}") public StockCountView get(@PathVariable UUID id) { return service.get(id); }
    @GetMapping("/resources") public StockCountResources resources() { return service.resources(); }
    @GetMapping("/balances") public List<StockCountResources.Balance> balances(@RequestParam UUID warehouseId) {
        return service.balances(warehouseId);
    }
    @GetMapping(value = "/{id}/export.xlsx", produces = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    public ResponseEntity<byte[]> exportExcel(@PathVariable UUID id) {
        return ResponseEntity.ok().header("Content-Disposition", "attachment; filename=\"inventario.xlsx\"")
                .body(exports.excel(id));
    }
    @GetMapping(value = "/{id}/export.pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> exportPdf(@PathVariable UUID id) {
        return ResponseEntity.ok().header("Content-Disposition", "attachment; filename=\"inventario.pdf\"")
                .body(exports.pdf(id));
    }
    @PutMapping("/{id}/lines/{productId}")
    public StockCountView upsertLine(@PathVariable UUID id, @PathVariable UUID productId,
                                     @Valid @RequestBody CountLineRequest request) {
        return service.upsertLine(id, productId, request.countedQuantity());
    }
    @PutMapping("/{id}/draft")
    public StockCountView saveDraft(@PathVariable UUID id, @Valid @RequestBody DraftRequest request) {
        return service.saveDraft(id, request.expectedVersion(), request.documentDate(), request.notes(), request.lines());
    }
    @PostMapping("/{id}/confirm")
    public StockCountView confirm(@PathVariable UUID id,
                                  @Valid @RequestBody(required = false) ReviewRequest review,
                                  Authentication authentication) {
        return service.confirm(id, review == null ? null : review.expectedVersion(), review == null ? null : review.lines(), authentication);
    }
    @PostMapping("/{id}/cancel")
    public StockCountView cancel(@PathVariable UUID id, Authentication authentication) {
        return service.cancel(id, authentication);
    }
    public record CreateRequest(@NotNull UUID warehouseId, String notes) {}
    public record CountLineRequest(@NotNull @DecimalMin("0") @Digits(integer = 16, fraction = 3) BigDecimal countedQuantity) {}
    public record DraftRequest(@NotNull @PositiveOrZero Long expectedVersion, @NotNull LocalDate documentDate,
                               String notes, @NotNull List<@NotNull @Valid DraftLineRequest> lines) {}
    public record DraftLineRequest(@NotNull UUID productId,
                                   @DecimalMin("0") @Digits(integer = 16, fraction = 3) BigDecimal countedQuantity,
                                   @Digits(integer = 16, fraction = 3) BigDecimal expectedQuantity) {}
    public record ReviewRequest(@NotNull List<StockCountView.Line> lines, @PositiveOrZero Long expectedVersion) {}
}
