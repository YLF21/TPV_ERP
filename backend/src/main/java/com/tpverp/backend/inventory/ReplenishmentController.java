package com.tpverp.backend.inventory;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import com.tpverp.backend.inventory.ReplenishmentService.Item;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/replenishment-requests")
@PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('GESTION_ALMACEN','STOCK_READ')")
public class ReplenishmentController {
    private final ReplenishmentService service;
    private final ReplenishmentExportService exports;
    public ReplenishmentController(ReplenishmentService service, ReplenishmentExportService exports) {
        this.service = service; this.exports = exports;
    }

    @GetMapping
    public List<ReplenishmentView> list(@RequestParam(required = false) List<UUID> warehouseId,
            @RequestParam(required = false) List<UUID> supplierId, @RequestParam(required = false) List<UUID> familyId,
            @RequestParam(required = false) List<UUID> subfamilyId, @RequestParam(required = false) String search) {
        return service.listFiltered(warehouseId, supplierId, familyId, subfamilyId, search, null);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('GESTION_ALMACEN','STOCK_READ','VENTA','GESTION_PRODUCTO','GESTION_VENTAS')")
    public void create(@Valid @RequestBody Create command) {
        service.create(command.productId(), command.warehouseId(), command.quantity(), command.reason());
    }

    @PutMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void edit(@PathVariable UUID id, @Valid @RequestBody Edit command) {
        service.edit(id, command.version(), command.quantity(), command.reason());
    }

    @PostMapping("/status")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void status(@Valid @RequestBody Status command) { service.changeStatus(command.items(), command.ordered()); }

    @PostMapping("/delete")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@Valid @RequestBody Selection command) { service.delete(command.items()); }

    @GetMapping("/export")
    public ResponseEntity<byte[]> export(@RequestParam String format,
            @RequestParam(defaultValue = "es") String locale, @RequestParam(required = false) List<UUID> ids,
            @RequestParam(required = false) List<UUID> warehouseId, @RequestParam(required = false) List<UUID> supplierId,
            @RequestParam(required = false) List<UUID> familyId, @RequestParam(required = false) List<UUID> subfamilyId,
            @RequestParam(required = false) String search) {
        if (!List.of("xlsx", "pdf").contains(format)) throw new IllegalArgumentException("Formato no válido");
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(format.equals("pdf") ? "application/pdf"
                : "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("pendiente-reposicion." + format).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(exports.export(format, locale, service.listFiltered(warehouseId, supplierId, familyId, subfamilyId, search, ids)));
    }

    @PostMapping("/export")
    public ResponseEntity<byte[]> exportSelection(@Valid @RequestBody Export command) {
        return export(command.format(), command.locale(), command.ids(), filters(command.warehouseIds(), command.warehouseId()),
                filters(command.supplierIds(), command.supplierId()), filters(command.familyIds(), command.familyId()),
                filters(command.subfamilyIds(), command.subfamilyId()), command.search());
    }

    private static List<UUID> filters(List<UUID> values, UUID legacy) {
        return values == null ? ReplenishmentService.single(legacy) : values;
    }

    public record Create(@NotNull UUID productId, @NotNull UUID warehouseId,
            @NotNull @Positive @Digits(integer = 16, fraction = 3) BigDecimal quantity,
            @NotBlank @Size(max = 500) String reason) {}
    public record Edit(@Min(0) long version, @NotNull @Positive @Digits(integer = 16, fraction = 3) BigDecimal quantity,
            @NotBlank @Size(max = 500) String reason) {}
    public record Status(@NotEmpty @Size(max = 5000) List<@NotNull Item> items, boolean ordered) {}
    public record Selection(@NotEmpty @Size(max = 5000) List<@NotNull Item> items) {}
    public record Export(@NotBlank String format, @Size(max = 16) String locale,
            @Size(max = 5000) List<@NotNull UUID> ids, UUID warehouseId, UUID supplierId,
            UUID familyId, UUID subfamilyId, @Size(max = 120) String search,
            @Size(max = 500) List<@NotNull UUID> warehouseIds, @Size(max = 500) List<@NotNull UUID> supplierIds,
            @Size(max = 500) List<@NotNull UUID> familyIds, @Size(max = 500) List<@NotNull UUID> subfamilyIds) {}
}
