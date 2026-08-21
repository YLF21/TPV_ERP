package com.tpverp.saas.loyalty;

import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MemberWalletBootstrapStatusService {

    private final SaasMemberWalletBootstrapRepository bootstraps;
    private final SaasMemberWalletBootstrapStoreRepository stores;

    public MemberWalletBootstrapStatusService(
            SaasMemberWalletBootstrapRepository bootstraps,
            SaasMemberWalletBootstrapStoreRepository stores) {
        this.bootstraps = bootstraps;
        this.stores = stores;
    }

    @Transactional(readOnly = true)
    public LoyaltyApiModels.WalletBootstrapStatus latest(UUID companyId) {
        SaasMemberWalletBootstrap bootstrap = bootstraps
                .findFirstByCompany_IdOrderByCreatedAtDesc(companyId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "No existe bootstrap historico para la empresa"));
        return status(bootstrap);
    }

    @Transactional(readOnly = true)
    public LoyaltyApiModels.WalletBootstrapStatus status(SaasMemberWalletBootstrap bootstrap) {
        List<SaasMemberWalletBootstrapStore> expected = stores
                .findByBootstrap_IdOrderByStoreIdAsc(bootstrap.getId());
        List<UUID> expectedIds = expected.stream()
                .map(SaasMemberWalletBootstrapStore::getStoreId)
                .toList();
        List<UUID> completedIds = expected.stream()
                .filter(value -> value.getCompletedAt() != null)
                .map(SaasMemberWalletBootstrapStore::getStoreId)
                .toList();
        List<UUID> missingIds = expected.stream()
                .filter(value -> value.getCompletedAt() == null)
                .map(SaasMemberWalletBootstrapStore::getStoreId)
                .toList();
        List<UUID> conflictIds = expected.stream()
                .filter(value -> value.getConflictReason() != null)
                .map(SaasMemberWalletBootstrapStore::getStoreId)
                .toList();
        return new LoyaltyApiModels.WalletBootstrapStatus(
                bootstrap.getId(),
                bootstrap.getCompanyId(),
                bootstrap.getStatus(),
                bootstrap.getCutoffAt(),
                expectedIds,
                completedIds,
                missingIds,
                conflictIds,
                bootstrap.getConflictReason(),
                bootstrap.getCreatedAt(),
                bootstrap.getCompletedAt());
    }
}
