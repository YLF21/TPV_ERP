package com.tpverp.backend.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.Column;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SyncEventTest {

    @Test
    void outboxNacePendienteYPermiteMarcarEnvio() {
        UUID companyId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UUID terminalId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-06-30T10:00:00Z");
        SyncOutboxEvent event = new SyncOutboxEvent(
                companyId,
                storeId,
                terminalId,
                "DOCUMENTO",
                documentId,
                SyncOperation.CONFIRMAR,
                Map.of("total", "12.50"),
                createdAt);

        assertThat(event.getEventId()).isNotNull();
        assertThat(event.getCompanyId()).isEqualTo(companyId);
        assertThat(event.getStoreId()).isEqualTo(storeId);
        assertThat(event.getTerminalId()).isEqualTo(terminalId);
        assertThat(event.getEntityType()).isEqualTo("DOCUMENTO");
        assertThat(event.getEntityId()).isEqualTo(documentId);
        assertThat(event.getStatus()).isEqualTo(SyncOutboxStatus.PENDIENTE);
        assertThat(event.getAttempts()).isZero();

        event.markSending();
        event.markSent(Instant.parse("2026-06-30T10:01:00Z"));

        assertThat(event.getStatus()).isEqualTo(SyncOutboxStatus.ENVIADO);
        assertThat(event.getSentAt()).isEqualTo(Instant.parse("2026-06-30T10:01:00Z"));
        assertThat(event.getAttempts()).isOne();
    }

    @Test
    void outboxRegistraErrorSinPerderEvento() {
        SyncOutboxEvent event = new SyncOutboxEvent(
                UUID.randomUUID(),
                null,
                null,
                "CLIENTE",
                UUID.randomUUID(),
                SyncOperation.ACTUALIZAR,
                Map.of("nombre", "Cliente"),
                Instant.parse("2026-06-30T10:00:00Z"));

        event.markSending();
        event.markError("timeout");

        assertThat(event.getStatus()).isEqualTo(SyncOutboxStatus.ERROR);
        assertThat(event.getAttempts()).isOne();
        assertThat(event.getLastError()).isEqualTo("timeout");
    }

    @Test
    void inboxMarcaProcesadoConResultado() {
        UUID eventId = UUID.randomUUID();
        UUID companyId = UUID.randomUUID();
        SyncInboxEvent event = new SyncInboxEvent(
                eventId,
                companyId,
                null,
                Instant.parse("2026-06-30T10:00:00Z"));

        event.markProcessed(SyncInboxResult.OK, Instant.parse("2026-06-30T10:02:00Z"), null);

        assertThat(event.getEventId()).isEqualTo(eventId);
        assertThat(event.getCompanyId()).isEqualTo(companyId);
        assertThat(event.isProcessed()).isTrue();
        assertThat(event.getResult()).isEqualTo(SyncInboxResult.OK);
        assertThat(event.getProcessedAt()).isEqualTo(Instant.parse("2026-06-30T10:02:00Z"));
    }

    @Test
    void exigeCamposMinimos() {
        assertThatThrownBy(() -> new SyncOutboxEvent(
                null,
                null,
                null,
                "DOCUMENTO",
                UUID.randomUUID(),
                SyncOperation.CREAR,
                Map.of(),
                Instant.now()))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("companyId");
    }

    @Test
    void mapeaEstadoOutboxALaColumnaSqlExistente() throws NoSuchFieldException {
        Column column = SyncOutboxEvent.class.getDeclaredField("status").getAnnotation(Column.class);

        assertThat(column.name()).isEqualTo("estado");
    }
}
