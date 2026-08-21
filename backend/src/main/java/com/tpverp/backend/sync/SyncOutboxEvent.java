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

    @Column(name = "store_sequence")
    private Long storeSequence;

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

    @Column(name = "proximo_intento_en")
    private Instant nextAttemptAt;

    @Column(name = "reclamado_en")
    private Instant claimedAt;

    @Column(name = "claim_token")
    private UUID claimToken;

    @Column(name = "ultimo_error", length = 1000)
    private String lastError;

    @Column(name = "enviado_en")
    private Instant sentAt;

    @Column(name = "actualizado_en", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected SyncOutboxEvent() {
    }

    public SyncOutboxEvent(
            UUID companyId,
            UUID storeId,
            UUID terminalId,
            Long storeSequence,
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
        if (storeSequence != null && storeSequence <= 0) {
            throw new IllegalArgumentException("storeSequence debe ser positiva");
        }
        this.storeSequence = storeSequence;
        this.entityType = required(entityType, "entityType");
        this.entityId = Objects.requireNonNull(entityId, "entityId");
        this.operation = Objects.requireNonNull(operation, "operation");
        this.payload = new LinkedHashMap<>(Objects.requireNonNull(payload, "payload"));
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.nextAttemptAt = createdAt;
        this.updatedAt = createdAt;
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
        this(companyId, storeId, terminalId, null,
                entityType, entityId, operation, payload, createdAt);
    }

    /**
     * Compatibilidad con consumidores antiguos. El worker utiliza claim(UUID, Instant).
     */
    public void markSending() {
        claim(UUID.randomUUID(), Instant.now());
    }

    /**
     * Compatibilidad con consumidores antiguos. El worker confirma con token.
     */
    public void markSent(Instant sentAt) {
        markSent(claimToken, sentAt);
    }

    /**
     * Compatibilidad con consumidores antiguos. Los fallos del worker usan
     * markRetry o markDeadLetter con una fecha calculada.
     */
    public void markError(String error) {
        var now = Instant.now();
        this.lastError = normalizedError(error);
        this.status = SyncOutboxStatus.ERROR;
        this.nextAttemptAt = now;
        this.claimedAt = null;
        this.claimToken = null;
        this.updatedAt = now;
    }

    public void claim(UUID token, Instant claimedAt) {
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(claimedAt, "claimedAt");
        if (status != SyncOutboxStatus.PENDIENTE
                && status != SyncOutboxStatus.ERROR
                && status != SyncOutboxStatus.ENVIANDO) {
            throw new IllegalStateException("El evento no admite claim en estado " + status);
        }
        this.status = SyncOutboxStatus.ENVIANDO;
        this.attempts++;
        this.nextAttemptAt = null;
        this.claimedAt = claimedAt;
        this.claimToken = token;
        this.updatedAt = claimedAt;
    }

    public boolean markSent(UUID token, Instant sentAt) {
        if (!isOwnedBy(token)) {
            return false;
        }
        this.sentAt = Objects.requireNonNull(sentAt, "sentAt");
        this.lastError = null;
        this.status = SyncOutboxStatus.ENVIADO;
        this.nextAttemptAt = null;
        this.claimedAt = null;
        this.claimToken = null;
        this.updatedAt = sentAt;
        return true;
    }

    public boolean markRetry(UUID token, String error, Instant nextAttemptAt, Instant updatedAt) {
        if (!isOwnedBy(token)) {
            return false;
        }
        this.lastError = normalizedError(error);
        this.status = SyncOutboxStatus.ERROR;
        this.nextAttemptAt = Objects.requireNonNull(nextAttemptAt, "nextAttemptAt");
        this.claimedAt = null;
        this.claimToken = null;
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        return true;
    }

    public boolean markDeadLetter(UUID token, String error, Instant updatedAt) {
        if (!isOwnedBy(token)) {
            return false;
        }
        this.lastError = normalizedError(error);
        this.status = SyncOutboxStatus.DEAD_LETTER;
        this.nextAttemptAt = null;
        this.claimedAt = null;
        this.claimToken = null;
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        return true;
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

    public Long getStoreSequence() {
        return storeSequence;
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

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public Instant getClaimedAt() {
        return claimedAt;
    }

    public UUID getClaimToken() {
        return claimToken;
    }

    public String getLastError() {
        return lastError;
    }

    public Instant getSentAt() {
        return sentAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    private boolean isOwnedBy(UUID token) {
        return status == SyncOutboxStatus.ENVIANDO
                && token != null
                && token.equals(claimToken);
    }

    private static String normalizedError(String error) {
        String value = required(error, "error");
        return value.length() <= 1000 ? value : value.substring(0, 1000);
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " es obligatorio");
        }
        return value.trim();
    }
}
