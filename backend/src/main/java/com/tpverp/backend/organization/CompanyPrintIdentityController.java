package com.tpverp.backend.organization;

import static com.tpverp.backend.security.application.CorePermissionBootstrap.GESTION_VENTAS;
import static com.tpverp.backend.security.application.CorePermissionBootstrap.PRODUCTS_READ;
import static com.tpverp.backend.security.application.CorePermissionBootstrap.VENTA;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/organization/current/print-identity")
public class CompanyPrintIdentityController {

    private final CurrentOrganization organization;

    public CompanyPrintIdentityController(CurrentOrganization organization) {
        this.organization = organization;
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('" + PRODUCTS_READ + "','"
            + GESTION_VENTAS + "','" + VENTA + "')")
    public CompanyPrintIdentityView current() {
        return CompanyPrintIdentityView.from(organization.currentCompany());
    }
}
