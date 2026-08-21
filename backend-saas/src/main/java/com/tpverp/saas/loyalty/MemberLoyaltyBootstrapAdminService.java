package com.tpverp.saas.loyalty;

import com.tpverp.saas.admin.AdminAuditService;
import com.tpverp.saas.license.SaasCompany;
import com.tpverp.saas.license.SaasStore;
import com.tpverp.saas.license.SaasStoreRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MemberLoyaltyBootstrapAdminService {

    private final SaasStoreRepository stores;
    private final SaasMemberLoyaltyBootstrapRepository bootstraps;
    private final AdminAuditService audit;
    private final EntityManager entityManager;
    private final Clock clock;

    public MemberLoyaltyBootstrapAdminService(
            SaasStoreRepository stores,
            SaasMemberLoyaltyBootstrapRepository bootstraps,
            AdminAuditService audit,
            EntityManager entityManager,
            Clock clock) {
        this.stores = stores;
        this.bootstraps = bootstraps;
        this.audit = audit;
        this.entityManager = entityManager;
        this.clock = clock;
    }

    @Transactional
    public LoyaltyApiModels.BootstrapSourceResponse designate(
            UUID companyId,
            LoyaltyApiModels.BootstrapSourceRequest request) {
        if (companyId == null || request == null || request.storeId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "companyId y storeId son obligatorios");
        }
        SaasCompany company = entityManager.find(SaasCompany.class, companyId, LockModeType.PESSIMISTIC_WRITE);
        if (company == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Empresa no encontrada");
        }
        SaasStore store = stores.findById(request.storeId())
                .filter(value -> value.getCompany().getId().equals(companyId))
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "La tienda no pertenece a la empresa indicada"));
        Instant now = clock.instant();
        SaasMemberLoyaltyBootstrap bootstrap = bootstraps.findById(companyId).orElse(null);
        if (bootstrap == null) {
            bootstrap = bootstraps.save(new SaasMemberLoyaltyBootstrap(companyId, store.getId(), now));
        } else if (bootstrap.isCompleted()) {
            if (!bootstrap.getSourceStoreId().equals(store.getId())) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "La tienda fuente no puede cambiarse despues del bootstrap");
            }
        } else {
            bootstrap.redesignate(store.getId(), now);
        }
        audit.log(
                "DESIGNATE_LOYALTY_BOOTSTRAP_SOURCE",
                "COMPANY",
                companyId.toString(),
                "storeId=" + store.getId());
        return response(bootstrap);
    }

    private LoyaltyApiModels.BootstrapSourceResponse response(SaasMemberLoyaltyBootstrap bootstrap) {
        return new LoyaltyApiModels.BootstrapSourceResponse(
                bootstrap.getCompanyId(),
                bootstrap.getSourceStoreId(),
                bootstrap.isCompleted(),
                bootstrap.getDesignatedAt(),
                bootstrap.getCompletedAt());
    }
}

