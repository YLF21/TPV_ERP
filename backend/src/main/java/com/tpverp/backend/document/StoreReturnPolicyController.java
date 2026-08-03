package com.tpverp.backend.document;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/return-policy")
public class StoreReturnPolicyController {

    private final StoreReturnPolicyService service;

    public StoreReturnPolicyController(StoreReturnPolicyService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('GESTION_VENTAS','VENTA')")
    public StoreReturnPolicyService.View current() {
        return service.current();
    }

    @PutMapping
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('GESTION_VENTAS')")
    public StoreReturnPolicyService.View update(
            @Valid @RequestBody Request request,
            Authentication authentication) {
        return service.update(request.policy(), authentication);
    }

    public record Request(@NotNull StoreReturnPolicy policy) {
    }
}
