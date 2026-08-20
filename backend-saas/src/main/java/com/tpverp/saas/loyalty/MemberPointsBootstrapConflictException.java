package com.tpverp.saas.loyalty;

import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public class MemberPointsBootstrapConflictException extends ResponseStatusException {
    private final Set<UUID> storeIds;
    public MemberPointsBootstrapConflictException(String reason, Set<UUID> storeIds) {
        super(HttpStatus.CONFLICT, reason); this.storeIds = storeIds == null ? Set.of() : Set.copyOf(storeIds);
    }
    public Set<UUID> getStoreIds(){return storeIds;}
}
