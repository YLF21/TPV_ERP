package com.tpverp.backend.sync;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "sync_inbox")
public class SyncInboxEvent {

    @Id
    private UUID id;

    @Column(name = "event_id", nullable = false, unique = true)
    private UUID eventId;

    @Column(name = "empresa_id", nullable = false)
    private UUID companyId;

    @Column(name = "tienda_id")
    private UUID storeId;

    @Column(name = "recibido_en", nullable = false)
    private Instant receivedAt;

    @Column(name = "procesado", nullable = false)
    private boolean processed;

    @Enumerated(EnumType.STRING)
    @Column(name = "resultado", length = 16)
    private SyncInboxResult result;

    @Column(name = "error")
    private String error;

    @Column(name = "procesado_en")
    private Instant processedAt;

    @Version
    private long version;

    protected SyncInboxEvent() {
    }

    public SyncInboxEvent(UUID eventId, UUID companyId, UUID storeId, Instant receivedAt) {
        this.id = UUID.randomUUID();
        this.eventId = Objects.requireNonNull(eventId, "eventId");
        this.companyId = Objects.requireNonNull(companyId, "companyId");
        this.storeId = storeId;
        this.receivedAt = Objects.requireNonNull(receivedAt, "receivedAt");
    }

    public void markProcessed(SyncInboxResult result, Instant processedAt, String error) {
        this.result = Objects.requireNonNull(result, "result");
        this.processedAt = Objects.requireNonNull(processedAt, "processedAt");
        this.error = error == null || error.isBlank() ? null : error.trim();
        this.processed = true;
    }

    public UUID getEventId() {
        return eventId;
    }

    public UUID getCompanyId() {
        return companyId;
    }

    public UUID getStoreId() {
        return storeId;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public boolean isProcessed() {
        return processed;
    }

    public SyncInboxResult getResult() {
        return result;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }
}
