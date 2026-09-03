package com.tpverp.backend.organization;

import com.tpverp.backend.security.gestion.GestionGroup;
import com.tpverp.backend.security.gestion.RequireGestionGroup;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/invoice-print-configuration")
@PreAuthorize("hasRole('ADMIN')")
@RequireGestionGroup(GestionGroup.CONFIGURACION)
public class InvoicePrintConfigurationController {

    private final InvoicePrintConfigurationService service;

    public InvoicePrintConfigurationController(InvoicePrintConfigurationService service) {
        this.service = service;
    }

    @GetMapping
    public InvoicePrintConfigurationService.Configuration get() {
        return service.configuration();
    }

    @PutMapping("/observations")
    public InvoicePrintConfigurationService.Configuration updateObservations(
            @Valid @RequestBody ObservationsRequest request) {
        return service.updateObservations(request.observations());
    }

    @PostMapping("/bank-accounts")
    public InvoicePrintConfigurationService.BankAccount addAccount(
            @Valid @RequestBody BankAccountRequest request) {
        return service.addAccount(request.bankName(), request.iban());
    }

    @PutMapping("/bank-accounts/{id}")
    public InvoicePrintConfigurationService.BankAccount updateAccount(
            @PathVariable UUID id, @Valid @RequestBody BankAccountRequest request) {
        return service.updateAccount(id, request.bankName(), request.iban());
    }

    @PatchMapping("/bank-accounts/{id}/active")
    public InvoicePrintConfigurationService.BankAccount setActive(
            @PathVariable UUID id, @RequestBody ActiveRequest request) {
        return service.setActive(id, request.active());
    }

    public record ObservationsRequest(@Size(max = 2000) String observations) {
    }

    public record BankAccountRequest(
            @NotBlank @Size(max = 120) String bankName,
            @NotBlank @Size(max = 42) String iban) {
    }

    public record ActiveRequest(boolean active) {
    }
}
