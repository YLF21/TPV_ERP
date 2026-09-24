package com.tpverp.backend.inventory;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.Digits;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;
import com.tpverp.backend.shared.api.PagedResult;

@RestController
@RequestMapping("/api/v1/warehouse-transfers")
@PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('GESTION_ALMACEN', 'STOCK_TRANSFER')")
public class WarehouseTransferDocumentController {
    private final WarehouseTransferDocumentService service;
    public WarehouseTransferDocumentController(WarehouseTransferDocumentService service) { this.service = service; }

    @GetMapping public PagedResult<WarehouseTransferDocumentService.ListItem> list(
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) WarehouseTransferDocument.Status status,
            @RequestParam(required = false) UUID sourceWarehouseId,
            @RequestParam(required = false) UUID targetWarehouseId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant before,
            @RequestParam(required = false) String search) {
        return service.list(page, limit, status, sourceWarehouseId, targetWarehouseId, from, before, search);
    }
    @GetMapping("/{id}") public WarehouseTransferDocumentService.View get(@PathVariable UUID id) {
        return service.get(id);
    }
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public WarehouseTransferDocumentService.View create(@Valid @RequestBody Request request,
                                                         Authentication authentication) {
        return service.create(request.command(), authentication);
    }
    @PutMapping("/{id}")
    public WarehouseTransferDocumentService.View update(@PathVariable UUID id, @Valid @RequestBody Request request) {
        return service.update(id, request.command());
    }
    @PostMapping("/{id}/confirm")
    public WarehouseTransferDocumentService.View confirm(@PathVariable UUID id,
            @RequestParam Long expectedVersion, Authentication authentication) {
        return service.confirm(id, expectedVersion, authentication);
    }
    @PostMapping("/{id}/cancel")
    public WarehouseTransferDocumentService.View cancel(@PathVariable UUID id) { return service.cancel(id); }

    public record Request(@NotNull UUID sourceWarehouseId, @NotNull UUID targetWarehouseId,
                          @Size(max = 4000) String notes, Long expectedVersion,
                          @NotEmpty @Size(max = WarehouseTransferDocumentService.MAX_DOCUMENT_LINES) List<@NotNull @Valid Line> lines,
                          LocalDate date, @Size(max = 120) String externalNumber,
                          WarehouseInputPriceSource priceSource,
                          @DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 2) BigDecimal globalDiscount) {
        WarehouseTransferDocumentService.Command command() {
            return new WarehouseTransferDocumentService.Command(sourceWarehouseId, targetWarehouseId, notes,
                    expectedVersion, lines.stream().map(line -> new WarehouseTransferDocumentService.LineCommand(
                            line.productId(), line.quantity(), line.unitPrice(), line.discount(),
                            line.priceOverridden(), line.productName())).toList(), date, externalNumber, priceSource, globalDiscount);
        }
    }
    public record Line(@NotNull UUID productId, @NotNull @Positive @Digits(integer = 16, fraction = 3) BigDecimal quantity,
                       @DecimalMin("0") @Digits(integer = 17, fraction = 3) BigDecimal unitPrice,
                       @DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 2) BigDecimal discount,
                       boolean priceOverridden, @Size(max = 255) String productName) {}
}
