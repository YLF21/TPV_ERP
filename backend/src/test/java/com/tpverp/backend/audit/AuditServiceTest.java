package com.tpverp.backend.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AuditServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-19T10:00:00Z");

    @Mock private AuditEntryRepository repository;
    @Mock private CurrentOrganization organization;
    @Mock private Store store;

    private AuditService service;
    private UUID storeId;

    @BeforeEach
    void setUp() {
        storeId = UUID.randomUUID();
        when(organization.currentStore()).thenReturn(store);
        when(store.getId()).thenReturn(storeId);
        service = new AuditService(
                repository, organization, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void queriesOnlyCurrentStoreAudit() {
        var from = NOW.minusSeconds(60);
        when(repository.findByTiendaIdAndCreadaEnBetweenOrderByCreadaEnDesc(
                storeId, from, NOW)).thenReturn(List.of());

        assertThat(service.query(from, NOW)).isEmpty();

        verify(repository).findByTiendaIdAndCreadaEnBetweenOrderByCreadaEnDesc(
                storeId, from, NOW);
    }

    @Test
    void rejectsAuditUuidFromAnotherStore() {
        var auditId = UUID.randomUUID();
        when(repository.findByIdAndTiendaId(auditId, storeId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(auditId, "ELIMINAR AUDITORIA"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("auditoría no encontrada");
    }
}
