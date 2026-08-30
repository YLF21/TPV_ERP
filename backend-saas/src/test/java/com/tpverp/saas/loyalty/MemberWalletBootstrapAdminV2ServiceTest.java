package com.tpverp.saas.loyalty;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tpverp.saas.admin.AdminAuditService;
import com.tpverp.saas.license.SaasCompany;
import com.tpverp.saas.license.SaasInstallationRepository;
import com.tpverp.saas.license.SaasStore;
import com.tpverp.saas.license.SaasStoreRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MemberWalletBootstrapAdminV2ServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-28T12:00:00Z");

    @Test
    void startEstableceElCutoffComunAntesDePublicarElBootstrap() {
        UUID companyId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        SaasStoreRepository stores = mock(SaasStoreRepository.class);
        SaasInstallationRepository installations = mock(SaasInstallationRepository.class);
        SaasMemberWalletBootstrapRepository bootstraps = mock(SaasMemberWalletBootstrapRepository.class);
        SaasMemberWalletBootstrapStoreRepository expectedStores = mock(SaasMemberWalletBootstrapStoreRepository.class);
        SaasMemberBalanceReservationRepository reservations = mock(SaasMemberBalanceReservationRepository.class);
        MemberWalletBootstrapStatusService statuses = mock(MemberWalletBootstrapStatusService.class);
        EntityManager entityManager = mock(EntityManager.class);
        SaasCompany company = mock(SaasCompany.class);
        SaasStore store = mock(SaasStore.class);
        when(entityManager.find(SaasCompany.class, companyId, LockModeType.PESSIMISTIC_WRITE))
                .thenReturn(company);
        when(bootstraps.findFirstByCompany_IdOrderByCreatedAtDesc(companyId))
                .thenReturn(Optional.empty());
        when(reservations.countLiveByCompany(companyId, NOW)).thenReturn(0L);
        when(stores.findByCompany_IdOrderByCodeAsc(companyId)).thenReturn(List.of(store));
        when(store.getId()).thenReturn(storeId);
        when(installations.existsByStore_IdAndActiveTrue(storeId)).thenReturn(true);
        when(bootstraps.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(statuses.status(any())).thenAnswer(invocation -> {
            SaasMemberWalletBootstrap bootstrap = invocation.getArgument(0);
            return new LoyaltyApiModels.WalletBootstrapStatus(
                    bootstrap.getId(), companyId, bootstrap.getStatus(), bootstrap.getCutoffAt(),
                    List.of(storeId), List.of(), List.of(storeId), List.of(), null,
                    bootstrap.getCreatedAt(), null);
        });

        var service = new MemberWalletBootstrapAdminV2Service(
                stores, installations, bootstraps, expectedStores, reservations, statuses,
                mock(AdminAuditService.class), entityManager, Clock.fixed(NOW, ZoneOffset.UTC));

        var status = service.start(companyId);

        assertThat(status.status()).isEqualTo(SaasMemberWalletBootstrap.COLLECTING);
        assertThat(status.cutoffAt()).isEqualTo(NOW);
    }
}
