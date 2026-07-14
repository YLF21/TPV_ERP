package com.tpverp.backend.party;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/suppliers")
public class SupplierController {

    private final SupplierService service;

    public SupplierController(SupplierService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('SUPPLIERS_READ')")
    public List<SupplierService.SupplierView> list() {
        return service.list();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('SUPPLIERS_READ')")
    public SupplierService.SupplierView get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('SUPPLIERS_WRITE')")
    public SupplierService.SupplierView create(@Valid @RequestBody SupplierRequest request) {
        return service.create(request.command());
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('SUPPLIERS_WRITE')")
    public SupplierService.SupplierView update(
            @PathVariable UUID id,
            @Valid @RequestBody SupplierRequest request) {
        return service.update(id, request.command());
    }

    @PatchMapping("/{id}/deactivate")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('SUPPLIERS_WRITE')")
    public ResponseEntity<Void> deactivate(@PathVariable UUID id) {
        service.deactivate(id);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{id}/activate")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('SUPPLIERS_WRITE')")
    public ResponseEntity<Void> activate(@PathVariable UUID id) {
        service.activate(id);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('SUPPLIERS_DELETE')")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/sales-representatives/{representativeId}")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('SUPPLIERS_WRITE')")
    public SupplierService.RepresentativeLinkView linkRepresentative(
            @PathVariable UUID id,
            @PathVariable UUID representativeId,
            @Valid @RequestBody RepresentativeLinkRequest request) {
        return service.linkRepresentative(id, representativeId, request.primary());
    }

    @DeleteMapping("/{id}/sales-representatives/{representativeId}")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('SUPPLIERS_WRITE')")
    public ResponseEntity<Void> unlinkRepresentative(
            @PathVariable UUID id,
            @PathVariable UUID representativeId) {
        service.unlinkRepresentative(id, representativeId);
        return ResponseEntity.noContent().build();
    }

    public record SupplierRequest(
            @NotBlank String legalName,
            String tradeName,
            @NotNull DocumentType documentType,
            @NotBlank String documentNumber,
            FiscalAddress address,
            String phone,
            String email,
            String notes) {

        SupplierService.SupplierCommand command() {
            return new SupplierService.SupplierCommand(
                    legalName, tradeName, documentType, documentNumber,
                    address, phone, email, notes);
        }
    }

    public record RepresentativeLinkRequest(boolean primary) {
    }
}
