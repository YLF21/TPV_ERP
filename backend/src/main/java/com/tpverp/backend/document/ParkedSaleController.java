package com.tpverp.backend.document;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/parked-sales")
public class ParkedSaleController {

    private final ParkedSaleService service;

    public ParkedSaleController(ParkedSaleService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('GESTION_VENTAS')")
    public List<ParkedSaleView> list() {
        return service.list().stream().map(ParkedSaleView::from).toList();
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('GESTION_VENTAS')")
    public ParkedSaleView park(
            @Valid @RequestBody ParkRequest request,
            Authentication authentication) {
        return ParkedSaleView.from(service.park(
                request.document().toCommand(), request.comment(), authentication));
    }

    @PostMapping("/{id}/open")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('GESTION_VENTAS')")
    public ParkedSaleOpened open(@PathVariable UUID id) {
        return service.openAndRemove(id);
    }

    public record ParkRequest(
            @NotNull @Valid DocumentRequest document,
            String comment) {
    }
}
