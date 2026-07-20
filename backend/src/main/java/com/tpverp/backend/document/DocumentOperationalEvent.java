package com.tpverp.backend.document;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "documento_evento_operativo")
public class DocumentOperationalEvent {

    @Id
    private UUID id;

    @Column(name = "documento_id", nullable = false)
    private UUID documentId;

    @Column(name = "tienda_id", nullable = false)
    private UUID storeId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private DocumentOperationalEventType tipo;

    @Column(name = "usuario_id", nullable = false)
    private UUID userId;

    @Column(name = "terminal_id")
    private UUID terminalId;

    @Column(name = "ocurrido_en", nullable = false)
    private Instant occurredAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> datos;

    protected DocumentOperationalEvent() {
    }

    public DocumentOperationalEvent(
            CommercialDocument document,
            DocumentOperationalEventType type,
            UUID userId,
            UUID terminalId,
            Instant occurredAt,
            Map<String, Object> data) {
        this.id = UUID.randomUUID();
        this.documentId = Objects.requireNonNull(document, "document").getId();
        this.storeId = document.getTiendaId();
        this.tipo = Objects.requireNonNull(type, "type");
        this.userId = Objects.requireNonNull(userId, "userId");
        this.terminalId = terminalId;
        this.occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
        this.datos = data == null ? new LinkedHashMap<>() : new LinkedHashMap<>(data);
    }

    public UUID getId() {
        return id;
    }

    public UUID getDocumentId() {
        return documentId;
    }

    public DocumentOperationalEventType getTipo() {
        return tipo;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getTerminalId() {
        return terminalId;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public Map<String, Object> getDatos() {
        return Map.copyOf(datos);
    }
}
