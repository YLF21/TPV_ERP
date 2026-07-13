package com.tpverp.backend.catalog;

import static com.tpverp.backend.security.application.CorePermissionBootstrap.GESTION_PRODUCTO;
import static com.tpverp.backend.security.application.CorePermissionBootstrap.PRODUCTS_READ;
import static com.tpverp.backend.security.application.CorePermissionBootstrap.PRODUCTS_WRITE;

import com.tpverp.backend.catalog.ProductSupplierService.ProductSupplierView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/products/{productId}/suppliers")
public class ProductSupplierController {

    private final ProductSupplierService service;

    public ProductSupplierController(ProductSupplierService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('" + PRODUCTS_READ + "','" + GESTION_PRODUCTO + "')")
    public List<ProductSupplierView> list(@PathVariable UUID productId) {
        return service.list(productId);
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('" + PRODUCTS_WRITE + "','" + GESTION_PRODUCTO + "')")
    public ProductSupplierView link(
            @PathVariable UUID productId, @Valid @RequestBody LinkRequest request) {
        return service.link(
                productId,
                request.supplierId(),
                request.supplierReference(),
                request.principal());
    }

    @PutMapping("/{supplierId}")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('" + PRODUCTS_WRITE + "','" + GESTION_PRODUCTO + "')")
    public ProductSupplierView update(
            @PathVariable UUID productId,
            @PathVariable UUID supplierId,
            @Valid @RequestBody ReferenceRequest request) {
        return service.update(
                productId, supplierId, request.supplierReference(), request.principal());
    }

    @DeleteMapping("/{supplierId}")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('" + PRODUCTS_WRITE + "','" + GESTION_PRODUCTO + "')")
    public ResponseEntity<Void> unlink(
            @PathVariable UUID productId, @PathVariable UUID supplierId) {
        service.unlink(productId, supplierId);
        return ResponseEntity.noContent().build();
    }

    public record LinkRequest(
            @NotNull UUID supplierId,
            @Size(max = 128) String supplierReference,
            Boolean principal) {
    }

    public record ReferenceRequest(
            @Size(max = 128) String supplierReference,
            Boolean principal) {
    }
}
