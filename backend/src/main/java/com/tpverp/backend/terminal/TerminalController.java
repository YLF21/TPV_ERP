package com.tpverp.backend.terminal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/terminals")
public class TerminalController {

    private final TerminalRegistrationService service;

    public TerminalController(TerminalRegistrationService service) {
        this.service = service;
    }

    @PostMapping("/request")
    public TerminalRegistrationService.RegistrationResult request(
            @Valid @RequestBody TerminalRequest request) {
        return service.request(request.tiendaId(), request.name(), request.type());
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('TERMINALS_MANAGE')")
    public List<TerminalRegistrationService.TerminalItem> list() {
        return service.list();
    }

    @PostMapping("/{terminalId}/approve")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('TERMINALS_MANAGE')")
    public TerminalRegistrationService.TerminalItem approve(@PathVariable UUID terminalId) {
        return service.approve(terminalId);
    }

    @PostMapping("/{terminalId}/deactivate")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('TERMINALS_MANAGE')")
    public ResponseEntity<Void> deactivate(@PathVariable UUID terminalId) {
        service.deactivate(terminalId);
        return ResponseEntity.noContent().build();
    }

    public record TerminalRequest(
            @NotNull UUID tiendaId,
            @NotBlank String name,
            @NotNull TipoTerminal type) {
    }
}
