package com.tpverp.backend.party;

import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/customers")
public class CustomerAdoptionController {
    private final CustomerAdoptionService service;
    public CustomerAdoptionController(CustomerAdoptionService service) { this.service = service; }

    @PostMapping("/central-lookup")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('CUSTOMERS_WRITE','GESTION_CLIENTE_PROVEEDOR','VENTA')")
    public CustomerAdoptionApi.Profile lookup(@Valid @RequestBody CustomerAdoptionApi.Lookup request) {
        return service.lookup(request);
    }

    @PostMapping("/adopt-central")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('CUSTOMERS_WRITE','GESTION_CLIENTE_PROVEEDOR','VENTA')")
    public CustomerAdoptionApi.Adopted adopt(@Valid @RequestBody CustomerAdoptionApi.Adopt request) {
        return service.adopt(request);
    }
}
