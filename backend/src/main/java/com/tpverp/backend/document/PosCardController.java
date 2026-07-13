package com.tpverp.backend.document;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/pos/card")
public class PosCardController {
    private final PosCardService service;

    public PosCardController(PosCardService service) { this.service = service; }

    @PostMapping("/quote")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('VENTA','TICKETS_CREATE')")
    public PosCashService.Quote quote(
            @Valid @RequestBody PosCashController.SaleRequest request, Authentication authentication) {
        return service.quote(request, authentication);
    }

    @PostMapping("/charge")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('VENTA','TICKETS_CREATE')")
    public PosCardService.Result charge(
            @Valid @RequestBody CardRequest request, Authentication authentication) {
        return service.charge(request, authentication);
    }

    public record CardRequest(
            @NotNull UUID checkoutId,
            @NotNull @Valid PosCashController.SaleRequest sale,
            @NotNull @DecimalMin("0.01") BigDecimal quotedTotal) {}
}
