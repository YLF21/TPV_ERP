package com.tpverp.backend.catalog;

import static com.tpverp.backend.security.application.CorePermissionBootstrap.PRODUCTS_DELETE;
import static com.tpverp.backend.security.application.CorePermissionBootstrap.PRODUCTS_READ;
import static com.tpverp.backend.security.application.CorePermissionBootstrap.PRODUCTS_WRITE;

import jakarta.validation.Valid;
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
@RequestMapping("/api/v1/products")
public class ProductController {

    private final CatalogService service;

    public ProductController(CatalogService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('" + PRODUCTS_READ + "')")
    public List<Product> list() {
        return service.products();
    }

    @GetMapping("/{productId}")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('" + PRODUCTS_READ + "')")
    public Product get(@PathVariable UUID productId) {
        return service.product(productId);
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('" + PRODUCTS_WRITE + "')")
    public Product create(@Valid @RequestBody CatalogService.ProductRequest request) {
        return service.createProduct(request);
    }

    @PutMapping("/{productId}")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('" + PRODUCTS_WRITE + "')")
    public Product update(
            @PathVariable UUID productId, @Valid @RequestBody CatalogService.ProductRequest request) {
        return service.updateProduct(productId, request);
    }

    @DeleteMapping("/{productId}")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('" + PRODUCTS_DELETE + "')")
    public ResponseEntity<Void> delete(@PathVariable UUID productId) {
        service.deleteProduct(productId);
        return ResponseEntity.noContent().build();
    }
}
