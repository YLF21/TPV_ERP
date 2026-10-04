package com.tpverp.backend.terminal;

import com.tpverp.backend.security.domain.UserAccount;
import com.tpverp.backend.security.gestion.GestionGroup;
import com.tpverp.backend.security.gestion.RequireGestionGroup;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/terminals/workstations")
public class WorkstationController {
    private final TerminalLinkingService service;
    public WorkstationController(TerminalLinkingService service) { this.service = service; }
    @GetMapping
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('TERMINALS_MANAGE')")
    @RequireGestionGroup(GestionGroup.SEGURIDAD)
    public TerminalLinkingService.ManagementView list() { return service.management(); }

    @PostMapping("/{code}/{action:approve|deactivate|release|cancel}")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('TERMINALS_MANAGE')")
    @RequireGestionGroup(GestionGroup.SEGURIDAD)
    public TerminalLinkingService.ManagementView action(@PathVariable String code, @PathVariable String action, @RequestBody ExpectedBinding request) {
        return service.action(code, request.bindingId(), action);
    }
    @GetMapping("/{code}/history")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('TERMINALS_MANAGE')")
    @RequireGestionGroup(GestionGroup.SEGURIDAD)
    public List<TerminalLinkingService.HistoryItem> history(@PathVariable String code) { return service.history(code); }
    @PostMapping("/legacy/{terminalId}/assign-code")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('TERMINALS_MANAGE')")
    @RequireGestionGroup(GestionGroup.SEGURIDAD)
    public TerminalLinkingService.ManagementView assign(@PathVariable UUID terminalId, @RequestBody CodeAssignment request) {
        return service.assignLegacy(terminalId, request.code());
    }
    @PostMapping("/server/adopt")
    @PreAuthorize("hasRole('ADMIN')")
    public TerminalLinkingService.LinkState adopt(@RequestBody TerminalLinkingService.ServerAdoption request,
            Authentication authentication, HttpServletRequest http) {
        if (authentication == null || !(authentication.getPrincipal() instanceof UserAccount administrator)
                || !administrator.isProtegido() || administrator.getTienda() != null)
            throw new TerminalLinkingException(HttpStatus.FORBIDDEN, "INSTALLATION_ADMIN_REQUIRED");
        return service.adoptServer(request, TerminalLinkingController.loopback(http), administrator);
    }
    public record ExpectedBinding(UUID bindingId) { }
    public record CodeAssignment(String code) { }
}
