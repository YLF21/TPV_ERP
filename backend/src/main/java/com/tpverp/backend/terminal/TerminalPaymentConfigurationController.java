package com.tpverp.backend.terminal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/terminal-configuration/payment")
public class TerminalPaymentConfigurationController {

    private final TerminalPaymentConfigurationService service;

    public TerminalPaymentConfigurationController(TerminalPaymentConfigurationService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('CONFIGURACION_TERMINAL','VENTA','GESTION_VENTAS','TICKETS_CREATE','INVOICES_WRITE')")
    public TerminalPaymentConfigurationView current() {
        return service.current();
    }

    @PatchMapping
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('CONFIGURACION_TERMINAL')")
    public TerminalPaymentConfigurationView update(@Valid @RequestBody UpdateRequest request) {
        return service.update(request.toCommand());
    }

    @PostMapping("/connection-test")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('CONFIGURACION_TERMINAL')")
    public TerminalPaymentConfigurationView recordConnectionTest(
            @Valid @RequestBody ConnectionTestRequest request) {
        return service.recordConnectionTest(new ConnectionTestResultCommand(request.success(), request.message()));
    }

    public record UpdateRequest(
            @NotNull PaymentCardMode cardMode,
            @NotNull PaymentTerminalProvider provider,
            String displayName,
            boolean enabled,
            boolean testMode,
            Map<String, String> providerParameters,
            String secretReference) {

        TerminalPaymentConfigurationCommand toCommand() {
            return new TerminalPaymentConfigurationCommand(
                    cardMode, provider, displayName, enabled, testMode, providerParameters, secretReference);
        }
    }

    public record ConnectionTestRequest(boolean success, String message) {
    }
}
