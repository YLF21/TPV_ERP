package com.tpverp.backend.document;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/parked-sales")
public class ParkedSaleController {

    private final ParkedSaleService service;
    private final PosCashService posSales;

    public ParkedSaleController(ParkedSaleService service, PosCashService posSales) {
        this.service = service;
        this.posSales = posSales;
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('GESTION_VENTAS') or hasAuthority('VENTA')")
    public List<ParkedSaleView> list() {
        return service.list().stream().map(ParkedSaleView::from).toList();
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('GESTION_VENTAS') or hasAuthority('VENTA')")
    public ParkedSaleView park(
            @Valid @RequestBody ParkRequest request,
            Authentication authentication) {
        return ParkedSaleView.from(service.park(
                request.document().toCommand(), request.comment(), authentication));
    }

    @PostMapping("/from-pos")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('GESTION_VENTAS') or hasAuthority('VENTA')")
    public ParkedSaleView parkFromPos(
            @Valid @RequestBody PosParkRequest request,
            Authentication authentication) {
        return ParkedSaleView.from(service.park(
                posSales.authoritativeCommand(request.sale(), authentication),
                request.comment(), authentication));
    }

    @PostMapping("/{id}/open")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('GESTION_VENTAS') or hasAuthority('VENTA')")
    public ParkedSaleOpened open(@PathVariable UUID id) {
        return service.open(id);
    }

    @PostMapping("/{id}/recoveries")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('GESTION_VENTAS') or hasAuthority('VENTA')")
    public ParkedSaleService.ParkedSaleRecoveryView recover(
            @PathVariable UUID id,
            @Valid @RequestBody RecoveryRequest request,
            Authentication authentication) {
        return service.recover(id, request.recoveryId(), authentication);
    }

    @PostMapping("/{id}/recoveries/{recoveryId}/acknowledge")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('GESTION_VENTAS') or hasAuthority('VENTA')")
    public ParkedSaleService.ParkedSaleRecoveryView acknowledge(
            @PathVariable UUID id, @PathVariable UUID recoveryId) {
        return service.acknowledge(id, recoveryId);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('GESTION_VENTAS') or hasAuthority('VENTA')")
    public void delete(@PathVariable UUID id) {
        service.delete(id);
    }

    public record ParkRequest(
            @NotNull @Valid DocumentRequest document,
            String comment) {
    }

    public record PosParkRequest(
            @NotNull @Valid PosCashController.SaleRequest sale,
            String comment) {
    }

    public record RecoveryRequest(@NotNull UUID recoveryId) {}
}
