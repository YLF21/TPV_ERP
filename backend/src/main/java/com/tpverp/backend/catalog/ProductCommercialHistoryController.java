package com.tpverp.backend.catalog;

import static com.tpverp.backend.security.application.CorePermissionBootstrap.GESTION_ALMACEN;
import static com.tpverp.backend.security.application.CorePermissionBootstrap.GESTION_PRODUCTO;
import static com.tpverp.backend.security.application.CorePermissionBootstrap.GESTION_VENTAS;
import static com.tpverp.backend.security.application.CorePermissionBootstrap.PRODUCTS_READ;
import static com.tpverp.backend.security.application.CorePermissionBootstrap.STOCK_READ;
import static com.tpverp.backend.security.application.CorePermissionBootstrap.VENTA;

import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/products")
public class ProductCommercialHistoryController {
    private final ProductCommercialHistoryService service;

    public ProductCommercialHistoryController(ProductCommercialHistoryService service) {
        this.service = service;
    }

    @GetMapping("/{productId}/commercial-history")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('" + PRODUCTS_READ + "','" + GESTION_PRODUCTO + "','" + GESTION_ALMACEN + "','" + GESTION_VENTAS + "','" + STOCK_READ + "','" + VENTA + "')")
    public List<ProductCommercialHistoryService.HistoryView> history(@PathVariable UUID productId) {
        return service.history(productId);
    }
}
