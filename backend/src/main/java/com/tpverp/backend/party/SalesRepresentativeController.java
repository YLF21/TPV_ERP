package com.tpverp.backend.party;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/sales-representatives")
public class SalesRepresentativeController {

    private final SalesRepresentativeService service;

    public SalesRepresentativeController(SalesRepresentativeService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('SUPPLIERS_READ')")
    public List<SalesRepresentativeService.SalesRepresentativeView> list() {
        return service.list();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('SUPPLIERS_READ')")
    public SalesRepresentativeService.SalesRepresentativeView get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('SUPPLIERS_WRITE')")
    public SalesRepresentativeService.SalesRepresentativeView create(
            @Valid @RequestBody SalesRepresentativeRequest request) {
        return service.create(request.command());
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('SUPPLIERS_WRITE')")
    public SalesRepresentativeService.SalesRepresentativeView update(
            @PathVariable UUID id,
            @Valid @RequestBody SalesRepresentativeRequest request) {
        return service.update(id, request.command());
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('SUPPLIERS_DELETE')")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    public record SalesRepresentativeRequest(
            @NotBlank String name,
            String phone,
            String email,
            String otherContact) {

        SalesRepresentativeService.SalesRepresentativeCommand command() {
            return new SalesRepresentativeService.SalesRepresentativeCommand(
                    name, phone, email, otherContact);
        }
    }
}
