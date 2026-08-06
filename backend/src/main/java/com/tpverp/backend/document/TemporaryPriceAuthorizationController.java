package com.tpverp.backend.document;

import com.tpverp.backend.security.sales.OperationAuthorizationRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/pos/sale-operation-authorizations/temporary-price")
@PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('VENTA','TICKETS_CREATE','GESTION_VENTAS')")
public class TemporaryPriceAuthorizationController {

    private final TemporaryPriceAuthorizationService service;

    public TemporaryPriceAuthorizationController(TemporaryPriceAuthorizationService service) {
        this.service = service;
    }

    @PostMapping
    public TemporaryPriceAuthorizationService.AuthorizationView authorize(
            @Valid @RequestBody Request request,
            Authentication authentication) {
        return service.authorize(
                request.productId(), request.cartLineId(), request.unitPrice(),
                request.authorization(), authentication);
    }

    public record Request(
            @NotNull UUID productId,
            @NotBlank @Size(max = 128) String cartLineId,
            @NotNull @DecimalMin("0.01") BigDecimal unitPrice,
            @Valid OperationAuthorizationRequest authorization) {
    }
}
