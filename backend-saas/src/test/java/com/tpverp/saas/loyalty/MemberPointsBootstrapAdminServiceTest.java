package com.tpverp.saas.loyalty;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

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
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;

class MemberPointsBootstrapAdminServiceTest {
    private static final Instant NOW=Instant.parse("2026-10-30T18:00:00.123456789Z");
    private final UUID companyId=UUID.randomUUID(), storeId=UUID.randomUUID();
    private final SaasMemberPointsBootstrapRepository bootstraps=mock(SaasMemberPointsBootstrapRepository.class);
    private MemberPointsBootstrapAdminService service;

    @BeforeEach
    void setUp() {
        var company=mock(SaasCompany.class);
        when(company.getId()).thenReturn(companyId);
        var store=mock(SaasStore.class);
        when(store.getId()).thenReturn(storeId);
        var stores=mock(SaasStoreRepository.class);
        when(stores.findByCompany_IdOrderByCodeAsc(companyId)).thenReturn(List.of(store));
        var installations=mock(SaasInstallationRepository.class);
        when(installations.existsByStore_IdAndActiveTrue(storeId)).thenReturn(true);
        var expected=mock(SaasMemberPointsBootstrapStoreRepository.class);
        var entityManager=mock(EntityManager.class);
        when(entityManager.find(SaasCompany.class,companyId,LockModeType.PESSIMISTIC_WRITE)).thenReturn(company);
        when(bootstraps.save(any())).thenAnswer(call->call.getArgument(0));
        service=new MemberPointsBootstrapAdminService(stores,installations,bootstraps,expected,
                mock(SaasMemberPointsAuthorityRepository.class),new MemberPointsBootstrapStatusService(bootstraps,expected),
                mock(AdminAuditService.class),entityManager,Clock.fixed(NOW,ZoneOffset.UTC));
    }

    @Test
    void publicaCorteCentralNoNuloConPrecisionPersistibleDesdeStartYStatus() {
        var started=service.start(companyId);
        var saved=ArgumentCaptor.forClass(SaasMemberPointsBootstrap.class);
        verify(bootstraps).save(saved.capture());
        Instant cutoff=Instant.parse("2026-10-30T18:00:00.123456Z");
        assertThat(started.cutoffAt()).isEqualTo(cutoff).isBefore(NOW);
        assertThat(saved.getValue().getCutoffAt()).isEqualTo(cutoff);
        assertThat(saved.getValue().getCreatedAt()).isEqualTo(cutoff);
        assertThat(cutoff.truncatedTo(ChronoUnit.MICROS)).isEqualTo(cutoff);
        when(bootstraps.findFirstByCompany_IdOrderByCreatedAtDesc(companyId)).thenReturn(Optional.of(saved.getValue()));
        assertThat(service.status(companyId).cutoffAt()).isEqualTo(started.cutoffAt());
    }

    @Test
    void reintentoStartNoReemplazaElCorteNiCreaOtroBootstrap() {
        var started=service.start(companyId);
        var saved=ArgumentCaptor.forClass(SaasMemberPointsBootstrap.class);
        verify(bootstraps).save(saved.capture());
        when(bootstraps.findFirstByCompany_IdOrderByCreatedAtDesc(companyId)).thenReturn(Optional.of(saved.getValue()));

        assertThatThrownBy(()->service.start(companyId)).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Debe cancelarse el bootstrap anterior");
        verify(bootstraps,times(1)).save(any());
        assertThat(service.status(companyId).cutoffAt()).isEqualTo(started.cutoffAt());
    }
}
