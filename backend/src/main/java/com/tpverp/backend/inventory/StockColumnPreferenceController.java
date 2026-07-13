package com.tpverp.backend.inventory;

import static com.tpverp.backend.security.application.CorePermissionBootstrap.GESTION_PRODUCTO;
import static com.tpverp.backend.security.application.CorePermissionBootstrap.STOCK_READ;
import static com.tpverp.backend.security.application.CorePermissionBootstrap.VENTA;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/stock/column-preferences")
public class StockColumnPreferenceController {

    private final StockColumnPreferenceService service;

    public StockColumnPreferenceController(StockColumnPreferenceService service) {
        this.service = service;
    }

    @GetMapping("/{app}")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('" + STOCK_READ + "','"
            + GESTION_PRODUCTO + "','" + VENTA + "')")
    public StockColumnPreferenceService.PreferenceView get(
            @PathVariable
            @NotBlank
            @Pattern(regexp = StockColumnPreferenceService.APP_PATTERN)
            String app,
            Authentication authentication) {
        return service.get(app, authentication);
    }

    @PutMapping("/{app}")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('" + STOCK_READ + "','"
            + GESTION_PRODUCTO + "','" + VENTA + "')")
    public StockColumnPreferenceService.PreferenceView save(
            @PathVariable
            @NotBlank
            @Pattern(regexp = StockColumnPreferenceService.APP_PATTERN)
            String app,
            @Valid @RequestBody StockColumnPreferenceService.SavePreferenceRequest request,
            Authentication authentication) {
        return service.save(app, request, authentication);
    }
}
