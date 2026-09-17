package com.tpverp.backend.document;

import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.terminal.CurrentTerminal;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Authenticated scope for locally queued control events; no credentials are returned. */
@RestController
public class SaleControlContextController {
    private final CurrentOrganization organization;
    private final CurrentTerminal terminal;
    private final Clock clock;

    public SaleControlContextController(CurrentOrganization organization, CurrentTerminal terminal, Clock clock) {
        this.organization = organization;
        this.terminal = terminal;
        this.clock = clock;
    }

    @GetMapping("/api/v1/sale-line-deletions/context")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('GESTION_VENTAS') or hasAuthority('VENTA')")
    public Context context(Authentication authentication) {
        return new Context(organization.currentStore().getId(), organization.currentUser(authentication).getId(),
                terminal.terminalId(authentication), clock.instant());
    }

    public record Context(UUID storeId, UUID userId, UUID terminalId, Instant serverTime) { }
}
