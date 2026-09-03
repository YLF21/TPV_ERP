package com.tpverp.backend.management;

import com.tpverp.backend.management.SafeManagementRetirementService.EntityType;
import com.tpverp.backend.security.gestion.GestionGroup;
import com.tpverp.backend.security.gestion.RequireGestionGroup;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Configuration-only endpoints for administrative management retirement. */
@RestController
@RequestMapping("/api/v1")
@RequireGestionGroup(GestionGroup.CONFIGURACION)
@PreAuthorize("hasRole('ADMIN')")
public class SafeManagementRetirementController {

    private final SafeManagementRetirementService retirement;

    public SafeManagementRetirementController(SafeManagementRetirementService retirement) {
        this.retirement = retirement;
    }

    @GetMapping("/products/management/page")
    public ManagementPage<ManagementItem> productsPage(
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int size,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) @Size(max = 120) String search,
            @RequestParam(required = false) Boolean active,
            @RequestParam(defaultValue = "name") @Size(max = 16) String sort,
            @RequestParam(defaultValue = "asc") @Size(max = 4) String direction) {
        return retirement.page(EntityType.PRODUCT, size, cursor, search, active, sort, direction);
    }

    @GetMapping("/customers/management/page")
    public ManagementPage<ManagementItem> customersPage(
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int size,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) @Size(max = 120) String search,
            @RequestParam(required = false) Boolean active,
            @RequestParam(defaultValue = "name") @Size(max = 16) String sort,
            @RequestParam(defaultValue = "asc") @Size(max = 4) String direction) {
        return retirement.page(EntityType.CUSTOMER, size, cursor, search, active, sort, direction);
    }

    @GetMapping("/suppliers/management/page")
    public ManagementPage<ManagementItem> suppliersPage(
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int size,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) @Size(max = 120) String search,
            @RequestParam(required = false) Boolean active,
            @RequestParam(defaultValue = "name") @Size(max = 16) String sort,
            @RequestParam(defaultValue = "asc") @Size(max = 4) String direction) {
        return retirement.page(EntityType.SUPPLIER, size, cursor, search, active, sort, direction);
    }

    @GetMapping("/sales-representatives/management/page")
    public ManagementPage<ManagementItem> representativesPage(
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int size,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) @Size(max = 120) String search,
            @RequestParam(required = false) Boolean active,
            @RequestParam(defaultValue = "name") @Size(max = 16) String sort,
            @RequestParam(defaultValue = "asc") @Size(max = 4) String direction) {
        return retirement.page(EntityType.SALES_REPRESENTATIVE, size, cursor, search, active, sort, direction);
    }

    @GetMapping("/sales-representatives/management/{id}")
    public ManagementItem representative(@PathVariable UUID id) {
        return retirement.representative(id);
    }

    @GetMapping("/products/management/{id}/retirement-impact")
    public SafeRetirementImpact productImpact(@PathVariable UUID id) {
        return retirement.impact(EntityType.PRODUCT, id);
    }

    @GetMapping("/customers/management/{id}/retirement-impact")
    public SafeRetirementImpact customerImpact(@PathVariable UUID id) {
        return retirement.impact(EntityType.CUSTOMER, id);
    }

    @GetMapping("/suppliers/management/{id}/retirement-impact")
    public SafeRetirementImpact supplierImpact(@PathVariable UUID id) {
        return retirement.impact(EntityType.SUPPLIER, id);
    }

    @GetMapping("/sales-representatives/management/{id}/retirement-impact")
    public SafeRetirementImpact representativeImpact(@PathVariable UUID id) {
        return retirement.impact(EntityType.SALES_REPRESENTATIVE, id);
    }

    @PostMapping("/products/management/{id}/retire")
    public SafeRetirementResult retireProduct(
            @PathVariable UUID id, @Valid @RequestBody SafeRetirementRequest request) {
        return retirement.retire(EntityType.PRODUCT, id, request.expectedVersion());
    }

    @PostMapping("/customers/management/{id}/retire")
    public SafeRetirementResult retireCustomer(
            @PathVariable UUID id, @Valid @RequestBody SafeRetirementRequest request) {
        return retirement.retire(EntityType.CUSTOMER, id, request.expectedVersion());
    }

    @PostMapping("/suppliers/management/{id}/retire")
    public SafeRetirementResult retireSupplier(
            @PathVariable UUID id, @Valid @RequestBody SafeRetirementRequest request) {
        return retirement.retire(EntityType.SUPPLIER, id, request.expectedVersion());
    }

    @PostMapping("/sales-representatives/management/{id}/retire")
    public SafeRetirementResult retireRepresentative(
            @PathVariable UUID id, @Valid @RequestBody SafeRetirementRequest request) {
        return retirement.retire(EntityType.SALES_REPRESENTATIVE, id, request.expectedVersion());
    }
}
