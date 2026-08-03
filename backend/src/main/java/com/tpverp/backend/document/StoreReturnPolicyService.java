package com.tpverp.backend.document;

import com.tpverp.backend.audit.AuditResult;
import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.organization.CurrentOrganization;
import java.util.Map;
import java.util.Objects;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StoreReturnPolicyService {

    private final StoreReturnConfigurationRepository configurations;
    private final CurrentOrganization organization;
    private final AuditService audit;

    public StoreReturnPolicyService(
            StoreReturnConfigurationRepository configurations,
            CurrentOrganization organization,
            AuditService audit) {
        this.configurations = configurations;
        this.organization = organization;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public View current() {
        return View.from(configuration());
    }

    @Transactional
    public View update(StoreReturnPolicy policy, Authentication authentication) {
        Objects.requireNonNull(policy, "policy");
        var configuration = configuration();
        var previous = configuration.getPolicy();
        configuration.update(policy);
        var saved = configurations.save(configuration);
        if (previous != policy) {
            var operator = organization.currentUser(authentication);
            audit.record(
                    "STORE_RETURN_POLICY_CHANGED",
                    AuditResult.EXITO,
                    Map.of(
                            "storeId", saved.getStoreId().toString(),
                            "previousPolicy", previous.name(),
                            "policy", policy.name(),
                            "operatorId", operator.getId().toString(),
                            "operatorUsername", operator.getUserName()));
        }
        return View.from(saved);
    }

    @Transactional(readOnly = true)
    public StoreReturnPolicy policy() {
        return configuration().getPolicy();
    }

    private StoreReturnConfiguration configuration() {
        var storeId = organization.currentStore().getId();
        return configurations.findById(storeId)
                .orElseGet(() -> new StoreReturnConfiguration(storeId));
    }

    public record View(java.util.UUID storeId, StoreReturnPolicy policy) {
        static View from(StoreReturnConfiguration configuration) {
            return new View(configuration.getStoreId(), configuration.getPolicy());
        }
    }
}
