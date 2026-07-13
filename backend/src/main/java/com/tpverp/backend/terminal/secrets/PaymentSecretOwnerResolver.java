package com.tpverp.backend.terminal.secrets;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.terminal.CurrentTerminal;
import org.springframework.security.core.context.SecurityContextHolder;
public final class PaymentSecretOwnerResolver {
    private final CurrentOrganization organization; private final CurrentTerminal terminal;
    public PaymentSecretOwnerResolver(CurrentOrganization organization,CurrentTerminal terminal){this.organization=organization;this.terminal=terminal;}
    public PaymentSecretOwnerScope current(){var store=organization.currentStore();return new PaymentSecretOwnerScope(store.getEmpresa().getId(),store.getId(),terminal.terminalId(SecurityContextHolder.getContext().getAuthentication()));}
}
