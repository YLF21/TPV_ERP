package com.tpverp.backend.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.Column;
import java.time.Instant;
import java.util.List;
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
    void replenishmentPayloadIsPurgedOnlyAfterMatchingClaimIsSent() {
        Instant createdAt = Instant.parse("2026-11-15T10:00:00Z");
        var event = new SyncOutboxEvent(UUID.randomUUID(), UUID.randomUUID(), null,
                "REPLENISHMENT_REQUEST", UUID.randomUUID(), SyncOperation.CREAR,
                Map.of("reason", "Reposición", "revision", 0), createdAt);
        UUID claim = UUID.randomUUID();

        assertThat(event.getPayload()).containsEntry("reason", "Reposición");
        event.claim(claim, createdAt.plusSeconds(1));
        assertThat(event.getPayload()).containsEntry("reason", "Reposición");
        assertThat(event.markSent(UUID.randomUUID(), createdAt.plusSeconds(2))).isFalse();
        assertThat(event.getPayload()).containsEntry("reason", "Reposición");
        assertThat(event.markSent(claim, createdAt.plusSeconds(2))).isTrue();
        assertThat(event.getStatus()).isEqualTo(SyncOutboxStatus.ENVIADO);
        assertThat(event.getPayload()).isEmpty();
    }

    @Test
    void inventoryDeliveryCompactsRowsAndRetainsManifestOnlyForTheCurrentClaim() {
        var event = inventoryEvent();
        var original = event.getPayload();
        UUID eventId = event.getEventId();
        UUID snapshotId = event.getEntityId();
        UUID oldClaim = UUID.randomUUID();
        UUID currentClaim = UUID.randomUUID();
        Instant createdAt = event.getCreatedAt();
        Instant sentAt = createdAt.plusSeconds(61);

        event.claim(oldClaim, createdAt.plusSeconds(1));
        event.claim(currentClaim, createdAt.plusSeconds(60));
        assertThat(event.markSent(oldClaim, sentAt)).isFalse();
        assertThat(event.getPayload()).isEqualTo(original);
        assertThat(event.getStatus()).isEqualTo(SyncOutboxStatus.ENVIANDO);

        assertThat(event.markSent(currentClaim, sentAt)).isTrue();
        assertThat(event.getPayload()).hasSize(original.size() - 3)
                .doesNotContainKeys("products", "warehouses", "balances");
        event.getPayload().forEach((key, value) -> assertThat(value).isEqualTo(original.get(key)));
        assertThat(event.getEventId()).isEqualTo(eventId);
        assertThat(event.getEntityId()).isEqualTo(snapshotId);
        assertThat(event.getStatus()).isEqualTo(SyncOutboxStatus.ENVIADO);
        assertThat(event.getSentAt()).isEqualTo(sentAt);
        assertThat(event.getAttempts()).isEqualTo(2);
    }

    @Test
    void inventoryDeliveryKeepsAllRowsForRetryAndManualRecovery() {
        var event = inventoryEvent();
        var original = event.getPayload();
        UUID firstClaim = UUID.randomUUID();
        UUID retryClaim = UUID.randomUUID();
        Instant now = event.getCreatedAt();

        event.claim(firstClaim, now.plusSeconds(1));
        assertThat(event.markRetry(firstClaim, "timeout", now.plusSeconds(10), now.plusSeconds(2))).isTrue();
        assertThat(event.getPayload()).isEqualTo(original);

        event.claim(retryClaim, now.plusSeconds(10));
        assertThat(event.markDeadLetter(retryClaim, "timeout", now.plusSeconds(11))).isTrue();
        assertThat(event.getPayload()).isEqualTo(original);

        event.reopenForManualRetry(now.plusSeconds(12));
        assertThat(event.getStatus()).isEqualTo(SyncOutboxStatus.PENDIENTE);
        assertThat(event.getPayload()).isEqualTo(original);
    }

    private static SyncOutboxEvent inventoryEvent() {
        UUID snapshotId = UUID.randomUUID();
        Instant capturedAt = Instant.parse("2026-11-15T10:00:00Z");
        return new SyncOutboxEvent(UUID.randomUUID(), UUID.randomUUID(), null,
                "INVENTORY_SNAPSHOT", snapshotId, SyncOperation.ACTUALIZAR, Map.ofEntries(
                        Map.entry("schemaVersion", 1), Map.entry("snapshotId", snapshotId.toString()),
                        Map.entry("sourceInstallationId", UUID.randomUUID().toString()),
                        Map.entry("capturedAt", capturedAt.toString()), Map.entry("chunkIndex", 0),
                        Map.entry("chunkCount", 1), Map.entry("productCount", 1), Map.entry("warehouseCount", 1),
                        Map.entry("balanceCount", 1), Map.entry("products", List.of(Map.of("name", "Producto"))),
                        Map.entry("warehouses", List.of(Map.of("name", "Almacén"))),
                        Map.entry("balances", List.of(Map.of("quantity", "2.125")))), capturedAt);
    }

    @Test
    void legacyPayloadRemainsAvailableAfterSent() {
        Instant createdAt = Instant.parse("2026-11-15T10:00:00Z");
        var event = new SyncOutboxEvent(UUID.randomUUID(), UUID.randomUUID(), null,
                "DOCUMENTO", UUID.randomUUID(), SyncOperation.CONFIRMAR,
                Map.of("numero", "T-1"), createdAt);
        UUID claim = UUID.randomUUID();
        event.claim(claim, createdAt.plusSeconds(1));

        assertThat(event.markSent(claim, createdAt.plusSeconds(2))).isTrue();
        assertThat(event.getPayload()).containsEntry("numero", "T-1");
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
