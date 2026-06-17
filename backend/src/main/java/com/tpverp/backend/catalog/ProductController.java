package com.tpverp.backend.catalog;

import static com.tpverp.backend.security.application.CorePermissionBootstrap.PRODUCTS_DELETE;
import static com.tpverp.backend.security.application.CorePermissionBootstrap.PRODUCTS_READ;
import static com.tpverp.backend.security.application.CorePermissionBootstrap.PRODUCTS_WRITE;

import jakarta.validation.Valid;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/products")
public class ProductController {

    private final CatalogService service;
    private final ProductImageService images;

    public ProductController(CatalogService service, ProductImageService images) {
        this.service = service;
        this.images = images;
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

    @PutMapping(path = "/{productId}/image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('" + PRODUCTS_WRITE + "')")
    public Product uploadImage(@PathVariable UUID productId, @RequestPart("file") MultipartFile file) {
        try {
            return images.upload(productId, file.getBytes());
        } catch (IOException exception) {
            throw new IllegalArgumentException("No se pudo recibir la imagen del producto", exception);
        }
    }
    // Recibe la imagen original y delega la conversion/almacenamiento al servicio de catalogo.

    @GetMapping(path = "/{productId}/image", produces = ProductImageService.CONTENT_TYPE)
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('" + PRODUCTS_READ + "')")
    public ResponseEntity<byte[]> image(
            @PathVariable UUID productId,
            @RequestParam(defaultValue = "false") boolean thumbnail) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(ProductImageService.CONTENT_TYPE))
                .body(images.read(productId, thumbnail).content());
    }

    @DeleteMapping("/{productId}/image")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('" + PRODUCTS_WRITE + "')")
    public Product deleteImage(@PathVariable UUID productId) {
        return images.delete(productId);
    }
}
