package com.tpverp.saas.loyalty;

import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public class MemberWalletBootstrapConflictException extends ResponseStatusException {

    private final Set<UUID> conflictStoreIds;

    public MemberWalletBootstrapConflictException(String reason, Set<UUID> conflictStoreIds) {
        super(HttpStatus.CONFLICT, reason);
        this.conflictStoreIds = Set.copyOf(conflictStoreIds);
    }

    public Set<UUID> getConflictStoreIds() {
        return conflictStoreIds;
    }
}
