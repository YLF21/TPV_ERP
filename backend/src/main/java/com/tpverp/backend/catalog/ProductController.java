package com.tpverp.backend.catalog;

import static com.tpverp.backend.security.application.CorePermissionBootstrap.PRODUCTS_DELETE;
import static com.tpverp.backend.security.application.CorePermissionBootstrap.GESTION_ALMACEN;
import static com.tpverp.backend.security.application.CorePermissionBootstrap.GESTION_PRODUCTO;
import static com.tpverp.backend.security.application.CorePermissionBootstrap.GESTION_VENTAS;
import static com.tpverp.backend.security.application.CorePermissionBootstrap.PRODUCTS_READ;
import static com.tpverp.backend.security.application.CorePermissionBootstrap.PRODUCTS_WRITE;
import static com.tpverp.backend.security.application.CorePermissionBootstrap.STOCK_READ;
import static com.tpverp.backend.security.application.CorePermissionBootstrap.VENTA;

import com.tpverp.backend.security.application.PermissionChecks;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
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
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('" + PRODUCTS_READ + "','" + GESTION_PRODUCTO + "','" + GESTION_ALMACEN + "','" + GESTION_VENTAS + "','" + STOCK_READ + "','" + VENTA + "')")
    public List<ProductView> list() {
        return service.products().stream().map(ProductView::publicView).toList();
    }

    @GetMapping("/management")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('" + GESTION_PRODUCTO + "')")
    public List<ProductView> managementList() {
        return service.products().stream().map(ProductView::managementView).toList();
    }

    @GetMapping("/management/{productId}")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('" + GESTION_PRODUCTO + "')")
    public ProductView managementGet(@PathVariable UUID productId) {
        return ProductView.managementView(service.product(productId));
    }

    @GetMapping("/{productId}")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('" + PRODUCTS_READ + "','" + GESTION_PRODUCTO + "','" + GESTION_ALMACEN + "','" + GESTION_VENTAS + "','" + STOCK_READ + "','" + VENTA + "')")
    public ProductView get(@PathVariable UUID productId) {
        return ProductView.publicView(service.product(productId));
    }

    @GetMapping("/{productId}/price-history")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('" + PRODUCTS_READ + "','" + GESTION_PRODUCTO + "','" + GESTION_ALMACEN + "','" + GESTION_VENTAS + "','" + STOCK_READ + "','" + VENTA + "')")
    public List<ProductPriceHistory> priceHistory(@PathVariable UUID productId, Authentication authentication) {
        var history = service.priceHistory(productId);
        if (PermissionChecks.hasProductManagement(authentication)) {
            return history;
        }
        return history.stream()
                .filter(entry -> entry.getType() != ProductPriceHistoryType.COSTE)
                .toList();
    }

    @PostMapping("/management")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('" + GESTION_PRODUCTO + "')")
    public ProductView managementCreate(@Valid @RequestBody CatalogService.ProductRequest request) {
        return ProductView.managementView(service.createProduct(request));
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('" + GESTION_PRODUCTO + "')")
    public ProductView create(@Valid @RequestBody CatalogService.ProductRequest request) {
        return ProductView.managementView(service.createProduct(request));
    }

    @PutMapping("/management/{productId}")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('" + GESTION_PRODUCTO + "')")
    public ProductView managementUpdate(
            @PathVariable UUID productId, @Valid @RequestBody CatalogService.ProductRequest request) {
        return ProductView.managementView(service.updateProduct(productId, request));
    }

    @PutMapping("/{productId}")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('" + GESTION_PRODUCTO + "')")
    public ProductView update(
            @PathVariable UUID productId, @Valid @RequestBody CatalogService.ProductRequest request) {
        return ProductView.managementView(service.updateProduct(productId, request));
    }

    @PatchMapping("/{productId}/active")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('" + GESTION_PRODUCTO + "')")
    public ProductView setActive(
            @PathVariable UUID productId, @Valid @RequestBody TaxController.ActiveRequest request) {
        return ProductView.managementView(service.setProductActive(productId, request.active()));
    }

    @DeleteMapping("/{productId}")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('" + PRODUCTS_DELETE + "','" + GESTION_PRODUCTO + "')")
    public ResponseEntity<Void> delete(@PathVariable UUID productId) {
        service.deleteProduct(productId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping(path = "/{productId}/image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('" + PRODUCTS_WRITE + "','" + GESTION_PRODUCTO + "')")
    public ProductView uploadImage(
            @PathVariable UUID productId,
            @RequestPart("file") MultipartFile file,
            Authentication authentication) {
        try {
            var product = images.upload(productId, file.getBytes());
            return PermissionChecks.hasProductManagement(authentication)
                    ? ProductView.managementView(product)
                    : ProductView.publicView(product);
        } catch (IOException exception) {
            throw new IllegalArgumentException("No se pudo recibir la imagen del producto", exception);
        }
    }
    // Receives the original image and delegates conversion/storage to the catalog service.

    @GetMapping(path = "/{productId}/image", produces = ProductImageService.CONTENT_TYPE)
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('" + PRODUCTS_READ + "','" + GESTION_PRODUCTO + "','" + GESTION_ALMACEN + "','" + GESTION_VENTAS + "','" + STOCK_READ + "','" + VENTA + "')")
    public ResponseEntity<byte[]> image(
            @PathVariable UUID productId,
            @RequestParam(defaultValue = "false") boolean thumbnail) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(ProductImageService.CONTENT_TYPE))
                .body(images.read(productId, thumbnail).content());
    }

    @DeleteMapping("/{productId}/image")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('" + GESTION_PRODUCTO + "')")
    public ProductView deleteImage(@PathVariable UUID productId) {
        return ProductView.managementView(images.delete(productId));
    }
}
