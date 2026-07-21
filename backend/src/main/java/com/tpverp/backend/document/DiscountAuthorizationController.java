package com.tpverp.backend.document;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/pos/discount-authorizations")
public class DiscountAuthorizationController {

    private final DiscountAuthorizationService service;

    public DiscountAuthorizationController(DiscountAuthorizationService service) {
        this.service = service;
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('VENTA','TICKETS_CREATE')")
    public DiscountAuthorizationService.AuthorizationResult authorize(
            @Valid @RequestBody AuthorizationRequest request,
            Authentication authentication) {
        return service.authorize(
                request.managerName(), request.password(), request.requestedPercent(), authentication);
    }

    public record AuthorizationRequest(
            @NotBlank String managerName,
            @NotBlank String password,
            @NotNull @DecimalMin("0.01") @DecimalMax("100.00") BigDecimal requestedPercent) {
    }
}
