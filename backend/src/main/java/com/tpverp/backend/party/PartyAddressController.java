package com.tpverp.backend.party;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/party-addresses")
@PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('CUSTOMERS_WRITE','SUPPLIERS_WRITE','GESTION_CLIENTE_PROVEEDOR','GESTION_ALMACEN')")
public class PartyAddressController {
    private final PartyAddressService service;

    public PartyAddressController(PartyAddressService service) {
        this.service = service;
    }

    @GetMapping("/suggestions")
    public PartyAddressService.Suggestions suggestions(
            @RequestParam @Size(max = 200) @Pattern(regexp = "[^\\p{Cntrl}]*") String q) {
        return service.suggestions(q);
    }

    @GetMapping("/resolve")
    public PartyAddressService.Resolved resolve(
            @RequestParam @NotBlank @Size(max = 200) @Pattern(regexp = "[^\\p{Cntrl}]*") String id,
            @RequestParam @Pattern(regexp = "portal|callejero") String type) {
        return service.resolve(id, type);
    }
}
