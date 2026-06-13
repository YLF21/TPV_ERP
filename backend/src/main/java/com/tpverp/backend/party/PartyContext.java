package com.tpverp.backend.party;

import com.tpverp.backend.organization.Empresa;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.security.domain.Usuario;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class PartyContext {

    private final CurrentOrganization organization;

    public PartyContext(CurrentOrganization organization) {
        this.organization = organization;
    }

    public Empresa currentCompany() {
        return organization.currentCompany();
    }

    public Usuario currentUser() {
        return organization.currentUser(
                SecurityContextHolder.getContext().getAuthentication());
    }
}
