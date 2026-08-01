package com.tpverp.backend.cash;

import com.tpverp.backend.organization.CurrentOrganization;
import java.time.Clock;
import java.time.Instant;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CashCurrentBalanceService {

    private final CashCurrentBalanceQueryRepository repository;
    private final CurrentOrganization organization;
    private final CashPermissionService permissions;
    private final Clock clock;

    public CashCurrentBalanceService(
            CashCurrentBalanceQueryRepository repository,
            CurrentOrganization organization,
            CashPermissionService permissions,
            Clock clock) {
        this.repository = repository;
        this.organization = organization;
        this.permissions = permissions;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public CashCurrentBalancesView current(Authentication authentication) {
        permissions.requireReportPermission(authentication);
        var store = organization.currentStore();
        return new CashCurrentBalancesView(
                Instant.now(clock),
                store.getTimezone(),
                repository.findCurrentBalances(store.getId()));
    }
}
