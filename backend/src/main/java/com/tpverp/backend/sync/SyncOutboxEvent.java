package com.tpverp.backend.sync;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "sync_outbox")
public class SyncOutboxEvent {

    @Id
    private UUID id;

    @Column(name = "event_id", nullable = false, unique = true)
    private UUID eventId;

    @Column(name = "empresa_id", nullable = false)
    private UUID companyId;

    @Column(name = "tienda_id")
    private UUID storeId;

    @Column(name = "terminal_id")
    private UUID terminalId;

    @Column(name = "tipo_entidad", nullable = false, length = 64)
    private String entityType;

    @Column(name = "entidad_id", nullable = false)
    private UUID entityId;

    @Enumerated(EnumType.STRING)
    @Column(name = "operacion", nullable = false, length = 32)
    private SyncOperation operation;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> payload;

    @Column(name = "creado_en", nullable = false)
    private Instant createdAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "estado", nullable = false, length = 16)
    private SyncOutboxStatus status = SyncOutboxStatus.PENDIENTE;

    @Column(name = "intentos", nullable = false)
    private int attempts;

    @Column(name = "ultimo_error")
    private String lastError;

    @Column(name = "enviado_en")
    private Instant sentAt;

    @Version
    private long version;

    protected SyncOutboxEvent() {
    }

    public SyncOutboxEvent(
            UUID companyId,
            UUID storeId,
            UUID terminalId,
            String entityType,
            UUID entityId,
            SyncOperation operation,
            Map<String, Object> payload,
            Instant createdAt) {
        this.id = UUID.randomUUID();
        this.eventId = UUID.randomUUID();
        this.companyId = Objects.requireNonNull(companyId, "companyId");
        this.storeId = storeId;
        this.terminalId = terminalId;
        this.entityType = required(entityType, "entityType");
        this.entityId = Objects.requireNonNull(entityId, "entityId");
        this.operation = Objects.requireNonNull(operation, "operation");
        this.payload = new LinkedHashMap<>(Objects.requireNonNull(payload, "payload"));
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    public void markSending() {
        status = SyncOutboxStatus.ENVIANDO;
        attempts++;
    }

    public void markSent(Instant sentAt) {
        this.sentAt = Objects.requireNonNull(sentAt, "sentAt");
        this.lastError = null;
        this.status = SyncOutboxStatus.ENVIADO;
    }

    public void markError(String error) {
        this.lastError = required(error, "error");
        this.status = SyncOutboxStatus.ERROR;
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

    public UUID getTerminalId() {
        return terminalId;
    }

    public String getEntityType() {
        return entityType;
    }

    public UUID getEntityId() {
        return entityId;
    }

    public SyncOperation getOperation() {
        return operation;
    }

    public Map<String, Object> getPayload() {
        return Map.copyOf(payload);
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public SyncOutboxStatus getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public String getLastError() {
        return lastError;
    }

    public Instant getSentAt() {
        return sentAt;
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " es obligatorio");
        }
        return value.trim();
    }
}
