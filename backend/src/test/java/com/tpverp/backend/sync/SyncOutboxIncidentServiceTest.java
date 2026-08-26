package com.tpverp.backend.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tpverp.backend.audit.AuditResult;
import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.data.domain.PageRequest;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class SyncOutboxIncidentServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-25T14:00:00Z");

    @Mock private SyncOutboxEventRepository events;
    @Mock private CurrentOrganization organization;
    @Mock private AuditService audit;
    @Mock private Company company;
    @Mock private Store store;

    private SyncOutboxIncidentService service;
    private UUID companyId;
    private UUID storeId;

    @BeforeEach
    void setUp() {
        companyId = UUID.randomUUID();
        storeId = UUID.randomUUID();
        when(organization.currentCompany()).thenReturn(company);
        when(organization.currentStore()).thenReturn(store);
        when(company.getId()).thenReturn(companyId);
        when(store.getId()).thenReturn(storeId);
        service = new SyncOutboxIncidentService(
                events, organization, audit, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void listaSoloDeadLettersDeLaEmpresaActual() {
        SyncOutboxEvent own = deadLetter(companyId, storeId);
        when(events.findIncidentsForStore(
                companyId, storeId, SyncOutboxStatus.DEAD_LETTER,
                PageRequest.of(0, 100))).thenReturn(List.of(own));

        List<SyncOutboxIncidentView> result = service.listDeadLetters();

        assertThat(result).extracting(SyncOutboxIncidentView::eventId)
                .containsExactly(own.getEventId());
    }

    @Test
    void reabreUnDeadLetterSinBorrarIntentosYAauditaElMotivo() {
        SyncOutboxEvent event = deadLetter(companyId, storeId);
        int attempts = event.getAttempts();
        when(events.findLockedByEventId(event.getEventId()))
                .thenReturn(Optional.of(event));

        SyncOutboxIncidentView result = service.retry(
                event.getEventId(), event.getVersion(), "Contrato corregido en SaaS");

        assertThat(result.status()).isEqualTo(SyncOutboxStatus.PENDIENTE);
        assertThat(result.attempts()).isEqualTo(attempts);
        assertThat(event.getNextAttemptAt()).isEqualTo(NOW);
        ArgumentCaptor<Map<String, Object>> details = ArgumentCaptor.forClass(Map.class);
        verify(audit).record(
                eq("SYNC_OUTBOX_MANUAL_RETRY"), eq(AuditResult.EXITO), details.capture());
        assertThat(details.getValue())
                .containsEntry("reason", "Contrato corregido en SaaS")
                .containsEntry("previousAttempts", attempts);
    }

    @Test
    void rechazaVersionObsoleta() {
        SyncOutboxEvent event = deadLetter(companyId, storeId);
        when(events.findLockedByEventId(event.getEventId()))
                .thenReturn(Optional.of(event));

        assertThatThrownBy(() -> service.retry(
                event.getEventId(), event.getVersion() + 1, "Reintento revisado"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("actualice el listado");
    }

    @Test
    void noPermiteReintentarElDeadLetterDeOtraTienda() {
        SyncOutboxEvent event = deadLetter(companyId, UUID.randomUUID());
        when(events.findLockedByEventId(event.getEventId()))
                .thenReturn(Optional.of(event));

        assertThatThrownBy(() -> service.retry(
                event.getEventId(), event.getVersion(), "Reintento cruzado"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("no encontrada");
    }

    private static SyncOutboxEvent deadLetter(UUID companyId, UUID storeId) {
        SyncOutboxEvent event = new SyncOutboxEvent(
                companyId, storeId, null, "FISCAL_STATUS",
                UUID.randomUUID(), SyncOperation.ACTUALIZAR, Map.of(), NOW.minusSeconds(60));
        UUID token = UUID.randomUUID();
        event.claim(token, NOW.minusSeconds(30));
        event.markDeadLetter(token, "Contrato incompatible", NOW.minusSeconds(20));
        return event;
    }
}
