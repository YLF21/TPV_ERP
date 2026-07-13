package com.tpverp.backend.terminal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "payment_terminal_event")
public class PaymentTerminalEvent {
    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "operation_id", nullable = false, updatable = false)
    private PaymentTerminalOperation operation;

    @Enumerated(EnumType.STRING)
    @Column(name = "previous_status", length = 32, updatable = false)
    private PaymentTerminalOperationStatus previousStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "new_status", nullable = false, length = 32, updatable = false)
    private PaymentTerminalOperationStatus newStatus;

    @Column(name = "normalized_code", length = 64, updatable = false)
    private String normalizedCode;

    @Column(name = "diagnostic", length = 512, updatable = false)
    private String diagnostic;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb", updatable = false)
    private Map<String, Object> metadata = Map.of();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected PaymentTerminalEvent() {}

    static PaymentTerminalEvent transition(PaymentTerminalOperation operation,
            PaymentTerminalOperationStatus previousStatus, PaymentTerminalOperationStatus newStatus,
            String normalizedCode, String diagnostic, Map<String, ?> metadata, Instant createdAt) {
        var event = new PaymentTerminalEvent();
        event.id = UUID.randomUUID();
        event.operation = Objects.requireNonNull(operation, "operation");
        event.previousStatus = previousStatus;
        event.newStatus = Objects.requireNonNull(newStatus, "newStatus");
        event.normalizedCode = optional(normalizedCode, 64, "normalizedCode");
        event.diagnostic = optional(PaymentTerminalSensitiveData.mask(diagnostic), 512, "diagnostic");
        event.metadata = immutableMetadata(metadata);
        event.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        return event;
    }

    public PaymentTerminalOperationStatus getPreviousStatus() { return previousStatus; }
    public PaymentTerminalOperationStatus getNewStatus() { return newStatus; }
    public String getNormalizedCode() { return normalizedCode; }
    public String getDiagnostic() { return diagnostic; }
    public Map<String, Object> getMetadata() { return metadata; }
    public Instant getCreatedAt() { return createdAt; }

    private static String optional(String value, int maximum, String field) {
        if (value == null || value.isBlank()) return null;
        var trimmed = value.trim();
        if (trimmed.length() > maximum) throw new IllegalArgumentException(field + " es demasiado largo");
        return trimmed;
    }

    private static Map<String, Object> immutableMetadata(Map<String, ?> values) {
        if (values == null || values.isEmpty()) return Map.of();
        var copy = new LinkedHashMap<String, Object>();
        values.forEach((key, value) -> {
            var safeKey = Objects.requireNonNull(key, "metadata key").trim();
            if (!safeKey.matches("[A-Za-z][A-Za-z0-9_.-]{0,63}")) {
                throw new IllegalArgumentException("Clave metadata invalida");
            }
            if (PaymentTerminalSensitiveData.sensitiveKey(safeKey)) return;
            copy.put(safeKey, immutableValue(value));
        });
        return Map.copyOf(copy);
    }

    private static Object immutableValue(Object value) {
        if (value instanceof String text) return PaymentTerminalSensitiveData.mask(text);
        if (value == null || value instanceof Number || value instanceof Boolean) return value;
        if (value instanceof Map<?, ?> map) {
            var typed = new LinkedHashMap<String, Object>();
            map.forEach((key, nested) -> typed.put(String.valueOf(key), nested));
            return immutableMetadata(typed);
        }
        if (value instanceof List<?> list) return list.stream().map(PaymentTerminalEvent::immutableValue).toList();
        throw new IllegalArgumentException("Tipo metadata no permitido");
    }
}
