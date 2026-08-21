package com.tpverp.saas.loyalty;

import com.tpverp.saas.admin.AdminAuditService;
import com.tpverp.saas.license.SaasCompany;
import com.tpverp.saas.license.SaasInstallationRepository;
import com.tpverp.saas.license.SaasStore;
import com.tpverp.saas.license.SaasStoreRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MemberWalletBootstrapAdminV2Service {

    private final SaasStoreRepository stores;
    private final SaasInstallationRepository installations;
    private final SaasMemberWalletBootstrapRepository bootstraps;
    private final SaasMemberWalletBootstrapStoreRepository expectedStores;
    private final SaasMemberBalanceReservationRepository reservations;
    private final MemberWalletBootstrapStatusService statuses;
    private final AdminAuditService audit;
    private final EntityManager entityManager;
    private final Clock clock;

    public MemberWalletBootstrapAdminV2Service(
            SaasStoreRepository stores,
            SaasInstallationRepository installations,
            SaasMemberWalletBootstrapRepository bootstraps,
            SaasMemberWalletBootstrapStoreRepository expectedStores,
            SaasMemberBalanceReservationRepository reservations,
            MemberWalletBootstrapStatusService statuses,
            AdminAuditService audit,
            EntityManager entityManager,
            Clock clock) {
        this.stores = stores;
        this.installations = installations;
        this.bootstraps = bootstraps;
        this.expectedStores = expectedStores;
        this.reservations = reservations;
        this.statuses = statuses;
        this.audit = audit;
        this.entityManager = entityManager;
        this.clock = clock;
    }

    @Transactional
    public LoyaltyApiModels.WalletBootstrapStatus start(UUID companyId) {
        SaasCompany company = lockCompany(companyId);
        Instant now = clock.instant();
        SaasMemberWalletBootstrap previous = bootstraps
                .findFirstByCompany_IdOrderByCreatedAtDesc(companyId)
                .orElse(null);
        if (previous != null) {
            if (previous.isCompleted()) {
                throw conflict("La empresa ya tiene un bootstrap historico COMPLETED");
            }
            if (SaasMemberWalletBootstrap.COLLECTING.equals(previous.getStatus())
                    || SaasMemberWalletBootstrap.RECONCILING.equals(previous.getStatus())) {
                throw conflict("Ya existe un bootstrap historico en curso");
            }
        }
        requireNoLiveReservations(companyId, now);

        List<SaasStore> companyStores = stores.findByCompany_IdOrderByCodeAsc(companyId);
        if (companyStores.isEmpty()) {
            throw conflict("La empresa no tiene tiendas para congelar en el bootstrap");
        }
        List<UUID> storesWithoutInstallation = companyStores.stream()
                .map(SaasStore::getId)
                .filter(storeId -> !installations.existsByCompany_IdAndStore_Id(companyId, storeId))
                .toList();
        if (!storesWithoutInstallation.isEmpty()) {
            throw conflict("Tiendas sin instalacion vinculada: " + storesWithoutInstallation);
        }

        SaasMemberWalletBootstrap bootstrap = bootstraps.save(new SaasMemberWalletBootstrap(
                UUID.randomUUID(),
                company,
                now));
        for (SaasStore store : companyStores) {
            expectedStores.save(new SaasMemberWalletBootstrapStore(
                    UUID.randomUUID(),
                    bootstrap,
                    store.getId()));
        }
        audit.log(
                "START_MEMBER_WALLET_BOOTSTRAP_V2",
                "COMPANY",
                companyId.toString(),
                "bootstrapId=" + bootstrap.getId() + "; expectedStoreIds="
                        + companyStores.stream().map(SaasStore::getId).toList());
        return statuses.status(bootstrap);
    }

    @Transactional(readOnly = true)
    public LoyaltyApiModels.WalletBootstrapStatus status(UUID companyId) {
        return statuses.latest(companyId);
    }

    @Transactional
    public LoyaltyApiModels.WalletBootstrapStatus cancel(UUID companyId, UUID bootstrapId) {
        lockCompany(companyId);
        SaasMemberWalletBootstrap bootstrap = bootstraps.findForUpdate(bootstrapId)
                .filter(value -> value.getCompanyId().equals(companyId))
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Bootstrap no encontrado para la empresa"));
        if (bootstrap.isCompleted()) {
            throw conflict("Un bootstrap COMPLETED no puede cancelarse");
        }
        if (!SaasMemberWalletBootstrap.CANCELLED.equals(bootstrap.getStatus())) {
            requireNoLiveReservations(companyId, clock.instant());
            bootstrap.cancel(clock.instant());
            audit.log(
                    "CANCEL_MEMBER_WALLET_BOOTSTRAP_V2",
                    "COMPANY",
                    companyId.toString(),
                    "bootstrapId=" + bootstrapId);
        }
        return statuses.status(bootstrap);
    }

    private SaasCompany lockCompany(UUID companyId) {
        if (companyId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "companyId es obligatorio");
        }
        SaasCompany company = entityManager.find(SaasCompany.class, companyId, LockModeType.PESSIMISTIC_WRITE);
        if (company == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Empresa no encontrada");
        }
        return company;
    }

    private void requireNoLiveReservations(UUID companyId, Instant now) {
        if (reservations.countLiveByCompany(companyId, now) > 0) {
            throw conflict("Existen reservas de monedero vivas; deben liberarse antes del bootstrap");
        }
    }

    private ResponseStatusException conflict(String reason) {
        return new ResponseStatusException(HttpStatus.CONFLICT, reason);
    }
}
