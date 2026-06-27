package com.tpverp.backend.party;

import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.security.domain.UserAccount;
import com.tpverp.backend.organization.Store;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class PartyContext {

    private final CurrentOrganization organization;

    public PartyContext(CurrentOrganization organization) {
        this.organization = organization;
    }

    public Company currentCompany() {
        return organization.currentCompany();
    }

    public Store currentStore() {
        return organization.currentStore();
    }

    public UserAccount currentUser() {
        return organization.currentUser(
                SecurityContextHolder.getContext().getAuthentication());
    }
}
