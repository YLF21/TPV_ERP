package com.tpverp.backend.inventory;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/stock-counts")
@PreAuthorize("hasRole('ADMIN') or hasAuthority('GESTION_ALMACEN')")
public class StockCountController {
    private final StockCountService service;
    public StockCountController(StockCountService service) { this.service = service; }

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
    @PutMapping("/{id}/lines/{productId}")
    public StockCountView upsertLine(@PathVariable UUID id, @PathVariable UUID productId,
                                     @Valid @RequestBody CountLineRequest request) {
        return service.upsertLine(id, productId, request.countedQuantity());
    }
    @PostMapping("/{id}/confirm")
    public StockCountView confirm(@PathVariable UUID id, Authentication authentication) {
        return service.confirm(id, authentication);
    }
    @PostMapping("/{id}/cancel")
    public StockCountView cancel(@PathVariable UUID id, Authentication authentication) {
        return service.cancel(id, authentication);
    }
    public record CreateRequest(@NotNull UUID warehouseId, String notes) {}
    public record CountLineRequest(@NotNull @DecimalMin("0") @Digits(integer = 16, fraction = 3) BigDecimal countedQuantity) {}
}
