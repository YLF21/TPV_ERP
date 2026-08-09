package com.tpverp.backend.promotion;

import static com.tpverp.backend.security.application.CorePermissionBootstrap.GESTION_VENTAS;
import static com.tpverp.backend.security.application.CorePermissionBootstrap.PRODUCTS_READ;
import static com.tpverp.backend.security.application.CorePermissionBootstrap.VENTA;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/products/sale/label-commercial-context")
public class ProductLabelCommercialContextController {

    private final ProductLabelCommercialContextService service;

    public ProductLabelCommercialContextController(ProductLabelCommercialContextService service) {
        this.service = service;
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('" + PRODUCTS_READ + "','"
            + GESTION_VENTAS + "','" + VENTA + "')")
    public List<ProductLabelCommercialContextService.ProductCommercialContextView> resolve(
            @Valid @RequestBody Request request) {
        return service.resolve(request.productIds());
    }

    public record Request(
            @NotEmpty @Size(max = 200) List<@NotNull UUID> productIds) {
    }
}
