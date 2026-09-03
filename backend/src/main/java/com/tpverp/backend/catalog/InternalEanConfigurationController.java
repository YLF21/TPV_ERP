package com.tpverp.backend.catalog;

import com.tpverp.backend.security.gestion.GestionGroup;
import com.tpverp.backend.security.gestion.RequireGestionGroup;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/internal-ean/configuration")
@RequireGestionGroup(GestionGroup.CONFIGURACION)
public class InternalEanConfigurationController {

    private final InternalEanConfigurationService service;

    public InternalEanConfigurationController(
            InternalEanConfigurationService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public InternalEanConfigurationService.View current() {
        return service.current();
    }

    @PutMapping
    @PreAuthorize("hasRole('ADMIN')")
    public InternalEanConfigurationService.View update(
            @Valid @RequestBody UpdateRequest request) {
        return service.update(request.expectedVersion(), request.companyCode());
    }

    public record UpdateRequest(
            @Min(0) long expectedVersion,
            @NotBlank @Pattern(regexp = "[0-9]{2}") String companyCode) {
    }
}
