package com.tpverp.backend.party.loyalty.category;

import com.tpverp.backend.organization.CurrentOrganization;
import org.springframework.stereotype.Service;

@Service
public class MemberCategoryAuthorityGuard {
    private final CurrentOrganization organization;
    private final MemberCategoryProjectionStateRepository states;

    public MemberCategoryAuthorityGuard(
            CurrentOrganization organization,
            MemberCategoryProjectionStateRepository states) {
        this.organization = organization;
        this.states = states;
    }

    public boolean centralizedOrThrow() {
        var state = states.findById(organization.currentStore().getId()).orElse(null);
        if (state == null || state.getStatus() == MemberCategoryProjectionStatus.LOCAL_ACTIVE) {
            return false;
        }
        if (state.getStatus() == MemberCategoryProjectionStatus.CENTRAL_ACTIVE) {
            return true;
        }
        throw new IllegalStateException(
                "La configuracion de categorias esta bloqueada durante la centralizacion");
    }

    public void requireLocalMutation() {
        if (centralizedOrThrow()) {
            throw new IllegalStateException(
                    "Las categorias deben modificarse mediante la autoridad central");
        }
    }
}
