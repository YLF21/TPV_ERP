package com.tpverp.backend.pdawork;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;

import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.security.domain.UserAccount;
import com.tpverp.backend.security.domain.UserAccountRepository;
import com.tpverp.backend.catalog.WarehouseRepository;
import com.tpverp.backend.catalog.ProductRepository;
import com.tpverp.backend.document.CommercialDocumentRepository;
import com.tpverp.backend.goodscheck.GoodsCheckRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.test.util.ReflectionTestUtils;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;

class PdaWorkServiceSecurityTest {
    private static final UUID STORE_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-08-25T10:00:00Z");

    @Test
    void assignedOperatorCannotBeFinishedByAnotherOperator() {
        var fixture = fixture();
        var owner = UUID.randomUUID();
        var intruder = UUID.randomUUID();
        var item = work(UUID.randomUUID());
        item.configure(owner, null, null, null, null, null, null);
        when(fixture.repository.findByIdAndStoreId(item.getId(), STORE_ID)).thenReturn(Optional.of(item));
        when(fixture.user.getId()).thenReturn(intruder);
        when(fixture.authentication.getAuthorities()).thenReturn(List.of());

        assertThatThrownBy(() -> fixture.service.finish(item.getId(), null, fixture.authentication))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void assignedOperatorCannotBeStartedByAnotherOperator() {
        var fixture = fixture();
        var owner = UUID.randomUUID();
        var intruder = UUID.randomUUID();
        var item = work(UUID.randomUUID());
        item.configure(owner, null, null, null, null, null, null);
        when(fixture.repository.findByIdAndStoreId(item.getId(), STORE_ID)).thenReturn(Optional.of(item));
        when(fixture.user.getId()).thenReturn(intruder);
        when(fixture.authentication.getAuthorities()).thenReturn(List.of());

        assertThatThrownBy(() -> fixture.service.start(item.getId(), null, fixture.authentication))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void assignedOperatorCannotReadAnotherOperatorsWork() {
        var fixture = fixture();
        var item = work(UUID.randomUUID());
        item.configure(UUID.randomUUID(), null, null, null, null, null, null);
        when(fixture.repository.findByIdAndStoreId(item.getId(), STORE_ID)).thenReturn(Optional.of(item));
        when(fixture.user.getId()).thenReturn(UUID.randomUUID());
        when(fixture.authentication.getAuthorities()).thenReturn(List.of());

        assertThatThrownBy(() -> fixture.service.get(item.getId(), fixture.authentication))
                .isInstanceOf(AccessDeniedException.class);
        verify(fixture.evidences, never()).findMetadataByWorkIdIn(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void operatorCannotRequestAnotherOperatorsAssignmentFilter() {
        var fixture = fixture();
        when(fixture.user.getId()).thenReturn(UUID.randomUUID());
        when(fixture.authentication.getAuthorities()).thenReturn(List.of());

        assertThatThrownBy(() -> fixture.service.list(null, null, UUID.randomUUID(), null, null,
                null, null, 0, 100, fixture.authentication)).isInstanceOf(AccessDeniedException.class);
        verify(fixture.repository, never()).findAll(org.mockito.ArgumentMatchers.any(org.springframework.data.jpa.domain.Specification.class),
                org.mockito.ArgumentMatchers.any(org.springframework.data.domain.Pageable.class));
    }

    @Test
    void assignedOperatorEvidenceCannotBeReadByAnotherOperator() {
        var fixture = fixture();
        var item = work(UUID.randomUUID());
        item.configure(UUID.randomUUID(), null, null, null, null, null, null);
        when(fixture.repository.findByIdAndStoreId(item.getId(), STORE_ID)).thenReturn(Optional.of(item));
        when(fixture.user.getId()).thenReturn(UUID.randomUUID());
        when(fixture.authentication.getAuthorities()).thenReturn(List.of());

        assertThatThrownBy(() -> fixture.service.evidence(item.getId(), UUID.randomUUID(), fixture.authentication))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void evidenceLimitIsCheckedWhileHoldingWorkLock() {
        var fixture = fixture();
        var item = work(UUID.randomUUID());
        when(fixture.repository.findByIdAndStoreIdForUpdate(item.getId(), STORE_ID)).thenReturn(Optional.of(item));
        when(fixture.user.getId()).thenReturn(item.getCreatedBy());
        when(fixture.authentication.getAuthorities()).thenReturn(List.of());
        when(fixture.evidences.countByWorkId(item.getId())).thenReturn(9L);
        when(fixture.evidences.save(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> invocation.getArgument(0));

        var saved = fixture.service.addEvidence(item.getId(),
                new PdaWorkService.EvidenceCommand("foto.jpg", "image/jpeg", "AQ==", null, 0L),
                fixture.authentication);

        assertThat(saved.name()).isEqualTo("foto.jpg");
        verify(fixture.repository).findByIdAndStoreIdForUpdate(item.getId(), STORE_ID);
        verify(fixture.evidences).countByWorkId(item.getId());
    }

    @Test
    void repeatedClientRequestReturnsPreviouslyCreatedWork() {
        var fixture = fixture();
        var requestId = UUID.randomUUID();
        var existing = work(requestId);
        when(fixture.repository.findByStoreIdAndClientRequestId(STORE_ID, requestId)).thenReturn(Optional.of(existing));
        when(fixture.evidences.findByWorkIdOrderByCreatedAt(existing.getId())).thenReturn(List.of());

        var view = fixture.service.create(new PdaWorkService.CreateCommand(PdaWorkType.TASK, "Conteo", null,
                null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, requestId), fixture.authentication);

        assertThat(view.id()).isEqualTo(existing.getId());
    }

    @Test
    void reusedClientRequestWithDifferentPayloadIsRejected() {
        var fixture = fixture();
        var requestId = UUID.randomUUID();
        var existing = work(requestId);
        existing.setClientRequestFingerprint("different-fingerprint");
        when(fixture.repository.findByStoreIdAndClientRequestId(STORE_ID, requestId)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> fixture.service.create(new PdaWorkService.CreateCommand(PdaWorkType.TASK,
                "Conteo", null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, requestId), fixture.authentication))
                .isInstanceOf(PdaWorkConflictException.class).hasMessageContaining("idempotencia");
    }

    @Test
    void assigneeMustBelongToCurrentStore() {
        var fixture = fixture();
        var foreignUser = UUID.randomUUID();
        when(fixture.users.findByIdAndTiendaId(foreignUser, STORE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> fixture.service.create(new PdaWorkService.CreateCommand(PdaWorkType.TASK,
                "Conteo", null, null, null, null, null, null, null, null, null, null, null,
                null, foreignUser, null, null, null, null, null, null, UUID.randomUUID()), fixture.authentication))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("tienda");
    }

    @Test
    void startReturnsVersionIncrementedByDatabaseFlush() {
        var fixture = fixture();
        var item = work(UUID.randomUUID());
        when(fixture.repository.findByIdAndStoreId(item.getId(), STORE_ID)).thenReturn(Optional.of(item));
        when(fixture.user.getId()).thenReturn(item.getCreatedBy());
        when(fixture.authentication.getAuthorities()).thenReturn(List.of());
        when(fixture.repository.saveAndFlush(item)).thenAnswer(invocation -> {
            ReflectionTestUtils.setField(item, "version", 1L);
            return invocation.getArgument(0);
        });
        when(fixture.evidences.findByWorkIdOrderByCreatedAt(item.getId())).thenReturn(List.of());

        var view = fixture.service.start(item.getId(), 0L, fixture.authentication);

        assertThat(view.version()).isEqualTo(1L);
        assertThat(view.status()).isEqualTo(PdaWorkStatus.IN_PROGRESS);
        verify(fixture.repository).saveAndFlush(item);
    }

    private static PdaWorkItem work(UUID clientRequestId) {
        return new PdaWorkItem(STORE_ID, PdaWorkType.TASK, "Conteo", null, null, null, null,
                null, null, null, null, null, null, null, null, UUID.randomUUID(), NOW, clientRequestId);
    }

    private static Fixture fixture() {
        var repository = mock(PdaWorkRepository.class);
        var evidences = mock(PdaWorkEvidenceRepository.class);
        var locations = mock(PdaWarehouseLocationRepository.class);
        var users = mock(UserAccountRepository.class);
        var warehouses = mock(WarehouseRepository.class);
        var products = mock(ProductRepository.class);
        var documents = mock(CommercialDocumentRepository.class);
        var goodsChecks = mock(GoodsCheckRepository.class);
        var organization = mock(CurrentOrganization.class);
        var store = mock(Store.class);
        var user = mock(UserAccount.class);
        var authentication = mock(Authentication.class);
        when(store.getId()).thenReturn(STORE_ID);
        when(organization.currentStore()).thenReturn(store);
        when(organization.currentUser(authentication)).thenReturn(user);
        return new Fixture(repository, evidences, users, user, authentication,
                new PdaWorkService(repository, evidences, locations, users, warehouses, products,
                        documents, goodsChecks, organization,
                        Clock.fixed(NOW, ZoneOffset.UTC)));
    }

    private record Fixture(PdaWorkRepository repository, PdaWorkEvidenceRepository evidences,
            UserAccountRepository users, UserAccount user, Authentication authentication, PdaWorkService service) {}
}
