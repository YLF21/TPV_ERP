package com.tpverp.backend.terminal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/terminal-configuration/interface")
public class TerminalInterfaceConfigurationController {

    private final TerminalInterfaceConfigurationService service;

    public TerminalInterfaceConfigurationController(TerminalInterfaceConfigurationService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('CONFIGURACION_TERMINAL','VENTA','GESTION_VENTAS','GESTION_PRODUCTO','GESTION_ALMACEN','GESTION_CUENTAS','TICKETS_CREATE','INVOICES_WRITE')")
    public TerminalInterfaceConfigurationService.View current() {
        return service.current();
    }

    @PatchMapping
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('CONFIGURACION_TERMINAL')")
    public TerminalInterfaceConfigurationService.View update(@Valid @RequestBody UpdateRequest request) {
        return service.update(request.saleMode());
    }

    public record UpdateRequest(@NotNull SaleInterfaceMode saleMode) {
    }
}
