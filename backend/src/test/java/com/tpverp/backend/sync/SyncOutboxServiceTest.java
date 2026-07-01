package com.tpverp.backend.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
}
