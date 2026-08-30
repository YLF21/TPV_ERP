package com.tpverp.backend.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@ExtendWith(MockitoExtension.class)
class SyncOutboxServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-30T12:00:00Z");

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    @Mock
    private SyncOutboxEventRepository repository;

    @Test
    void encolaEventoPendienteConIdentidadGlobal() {
        SyncOutboxService service = new SyncOutboxService(repository, clock);
        UUID companyId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UUID terminalId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();

        SyncOutboxEvent event = service.enqueue(new SyncOutboundEventCommand(
                companyId, storeId, terminalId, "DOCUMENTO", documentId,
                SyncOperation.CONFIRMAR, Map.of("numero", "T-1")));

        var saved = ArgumentCaptor.forClass(SyncOutboxEvent.class);
        verify(repository).save(saved.capture());
        assertThat(event).isSameAs(saved.getValue());
        assertThat(saved.getValue().getCompanyId()).isEqualTo(companyId);
        assertThat(saved.getValue().getStoreId()).isEqualTo(storeId);
        assertThat(saved.getValue().getTerminalId()).isEqualTo(terminalId);
        assertThat(saved.getValue().getEntityType()).isEqualTo("DOCUMENTO");
        assertThat(saved.getValue().getEntityId()).isEqualTo(documentId);
        assertThat(saved.getValue().getStatus()).isEqualTo(SyncOutboxStatus.PENDIENTE);
        assertThat(saved.getValue().getCreatedAt()).isEqualTo(NOW);
    }

    @Test
    void listaPendientesEnOrdenDeCreacion() {
        SyncOutboxService service = new SyncOutboxService(repository, clock);
        List<SyncOutboxEvent> pending = List.of(new SyncOutboxEvent(
                UUID.randomUUID(), null, null, "CLIENTE", UUID.randomUUID(),
                SyncOperation.ACTUALIZAR, Map.of(), NOW));
        when(repository.findByStatusOrderByCreatedAtAsc(SyncOutboxStatus.PENDIENTE))
                .thenReturn(pending);

        assertThat(service.pending()).isEqualTo(pending);
    }

    @Test
    void reclamaPorIdSinAfectarOtrosEventos() {
        UUID eventId = UUID.randomUUID();
        var event = new SyncOutboxEvent(UUID.randomUUID(), null, null, "DOCUMENTO",
                UUID.randomUUID(), SyncOperation.CONFIRMAR, Map.of(), NOW);
        when(repository.findClaimableByEventIdForUpdate(eventId, NOW, NOW.minusSeconds(120)))
                .thenReturn(java.util.Optional.of(event));

        var claimed = new SyncOutboxService(repository, clock).claimEvent(
                eventId, java.time.Duration.ofSeconds(120));

        assertThat(claimed).containsSame(event);
        assertThat(event.getStatus()).isEqualTo(SyncOutboxStatus.ENVIANDO);
        verify(repository).findClaimableByEventIdForUpdate(eventId, NOW, NOW.minusSeconds(120));
    }

    @Test
    void lasTransicionesDelEventoDirigidoAbrenTransaccionNuevaTrasElCommit() {
        assertThat(Arrays.stream(SyncOutboxService.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("claimEvent")
                        || method.getName().equals("markSent")
                        || method.getName().equals("markFailed")))
                .hasSize(3)
                .allSatisfy(method -> {
                    var transactional = method.getAnnotation(Transactional.class);
                    assertThat(transactional).isNotNull();
                    assertThat(transactional.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
                });
    }
}
