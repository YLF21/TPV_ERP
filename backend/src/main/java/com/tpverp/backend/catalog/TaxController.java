package com.tpverp.backend.catalog;

import static com.tpverp.backend.security.application.CorePermissionBootstrap.GESTION_PRODUCTO;
import static com.tpverp.backend.security.application.CorePermissionBootstrap.STOCK_READ;
import static com.tpverp.backend.security.application.CorePermissionBootstrap.TAXES_MANAGE;
import static com.tpverp.backend.security.application.CorePermissionBootstrap.VENTA;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/taxes")
public class TaxController {

    private final CatalogService service;

    public TaxController(CatalogService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('PRODUCTS_READ','" + STOCK_READ + "')")
    public List<StoreTax> list() {
        return service.taxes();
    }

    @GetMapping("/selectable")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('PRODUCTS_WRITE','" + GESTION_PRODUCTO + "','" + STOCK_READ + "','" + VENTA + "')")
    public List<StoreTax> selectable() {
        return service.selectableTaxes();
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('" + TAXES_MANAGE + "')")
    public StoreTax create(@Valid @RequestBody TaxRequest request) {
        return service.createTax(request.percentage());
    }

    @PutMapping("/{taxId}")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('" + TAXES_MANAGE + "')")
    public StoreTax update(@PathVariable UUID taxId, @Valid @RequestBody TaxRequest request) {
        return service.updateTax(taxId, request.percentage());
    }

    @DeleteMapping("/{taxId}")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('" + TAXES_MANAGE + "')")
    public org.springframework.http.ResponseEntity<Void> delete(@PathVariable UUID taxId) {
        service.deleteTax(taxId);
        return org.springframework.http.ResponseEntity.noContent().build();
    }

    @PatchMapping("/{taxId}/default")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('" + TAXES_MANAGE + "')")
    public StoreTax markDefault(@PathVariable UUID taxId) {
        return service.setDefaultTax(taxId);
    }

    @PatchMapping("/{taxId}/active")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('" + TAXES_MANAGE + "')")
    public StoreTax setActive(@PathVariable UUID taxId, @Valid @RequestBody ActiveRequest request) {
        return service.setTaxActive(taxId, request.active());
    }

    public record TaxRequest(
            @NotNull @DecimalMin("0.00") @DecimalMax("100.00") BigDecimal percentage) {
    }

    public record ActiveRequest(boolean active) {
    }
}
