package com.tpverp.backend.document;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/pos/cash")
public class PosCashController {

    private final PosCashService service;

    public PosCashController(PosCashService service) {
        this.service = service;
    }

    @PostMapping("/quote")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('VENTA','TICKETS_CREATE')")
    public PosCashService.Quote quote(@Valid @RequestBody SaleRequest request, Authentication authentication) {
        return service.quote(request, authentication);
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('VENTA','TICKETS_CREATE')")
    public PosCashService.Result charge(@Valid @RequestBody CashRequest request, Authentication authentication) {
        return service.charge(request, authentication);
    }

    public record SaleRequest(UUID customerId, @NotEmpty List<@Valid LineRequest> lines) {}

    public record LineRequest(
            @NotNull UUID productId,
            @NotNull @DecimalMin("0.01") BigDecimal quantity,
            @NotNull @DecimalMin("0.00") BigDecimal discount) {}

    public record CashRequest(
            @NotNull UUID checkoutId,
            @NotNull @Valid SaleRequest sale,
            @NotNull @DecimalMin("0.01") BigDecimal received,
            @NotNull @DecimalMin("0.01") BigDecimal quotedTotal) {}
}
