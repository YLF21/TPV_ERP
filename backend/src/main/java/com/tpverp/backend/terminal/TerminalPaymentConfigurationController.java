package com.tpverp.backend.terminal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
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
    public TerminalPaymentConfigurationView testConnection() {
        return service.testConnection();
    }

    @PostMapping("/pairing")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('CONFIGURACION_TERMINAL')")
    public PairingView pair(@Valid @RequestBody PairingRequest request) {
        return PairingView.from(service.pair(request.pairingId()));
    }

    @GetMapping("/pairing/{pairingId}")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('CONFIGURACION_TERMINAL')")
    public PairingView pairingStatus(@PathVariable UUID pairingId) {
        return PairingView.from(service.pairingStatus(pairingId));
    }

    public record PairingRequest(@NotNull UUID pairingId) {}
    public record PairingView(PaymentTerminalOperationStatus status, String code, String reference, String message) {
        static PairingView from(PaymentTerminalResult result) {
            return new PairingView(result.status(), result.code(), result.reference(), result.message());
        }
    }

    public record UpdateRequest(
            @NotNull PaymentCardMode cardMode,
            @NotNull PaymentTerminalProvider provider,
            String displayName,
            boolean enabled,
            boolean testMode,
            Map<String, String> providerParameters,
            String secretReference,
            Integer secretVersion) {

        TerminalPaymentConfigurationCommand toCommand() {
            return new TerminalPaymentConfigurationCommand(
                    cardMode, provider, displayName, enabled, testMode, providerParameters, secretReference,secretVersion);
        }
    }
}
