package com.tpverp.backend.document;

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
@Table(name = "ticket_anulacion_operacion")
public class TicketCancellationOperation {

    @Id
    private UUID id;

    @Column(name = "ticket_id", nullable = false)
    private UUID ticketId;

    @Column(name = "tienda_id", nullable = false)
    private UUID storeId;

    @Column(name = "terminal_id", nullable = false)
    private UUID terminalId;

    @Column(name = "operador_usuario_id", nullable = false)
    private UUID operatorUserId;

    @Column(name = "autorizador_usuario_id", nullable = false)
    private UUID authorizerUserId;

    @Column(name = "motivo", nullable = false, columnDefinition = "text")
    private String reason;

    @Column(name = "solicitud_hash", nullable = false, length = 64)
    private String requestHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "estado", nullable = false, length = 24)
    private TicketCancellationStatus status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "compensaciones_manuales", nullable = false, columnDefinition = "jsonb")
    private Map<String, String> manualCompensations;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "operaciones_tarjeta", nullable = false, columnDefinition = "jsonb")
    private Map<String, String> cardOperations;

    @Column(name = "mensaje_error", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "creado_en", nullable = false)
    private Instant createdAt;

    @Column(name = "actualizado_en", nullable = false)
    private Instant updatedAt;

    @Column(name = "completado_en")
    private Instant completedAt;

    @Version
    private long version;

    protected TicketCancellationOperation() {
    }

    public TicketCancellationOperation(
            UUID requestId,
            UUID ticketId,
            UUID storeId,
            UUID terminalId,
            UUID operatorUserId,
            UUID authorizerUserId,
            String reason,
            String requestHash,
            Map<String, String> manualCompensations,
            Instant now) {
        id = Objects.requireNonNull(requestId, "requestId");
        this.ticketId = Objects.requireNonNull(ticketId, "ticketId");
        this.storeId = Objects.requireNonNull(storeId, "storeId");
        this.terminalId = Objects.requireNonNull(terminalId, "terminalId");
        this.operatorUserId = Objects.requireNonNull(operatorUserId, "operatorUserId");
        this.authorizerUserId = Objects.requireNonNull(authorizerUserId, "authorizerUserId");
        this.reason = required(reason, "motivo");
        this.requestHash = required(requestHash, "requestHash");
        this.manualCompensations = new LinkedHashMap<>(
                manualCompensations == null ? Map.of() : manualCompensations);
        cardOperations = new LinkedHashMap<>();
        status = TicketCancellationStatus.PREPARED;
        createdAt = Objects.requireNonNull(now, "now");
        updatedAt = now;
    }

    public void requireCompatible(UUID expectedTicketId, String expectedHash) {
        if (!ticketId.equals(expectedTicketId) || !requestHash.equals(expectedHash)) {
            throw new IllegalStateException(
                    "el identificador de anulacion pertenece a otra solicitud");
        }
    }

    public void startCompensation(Instant now) {
        if (status == TicketCancellationStatus.COMPLETED) {
            return;
        }
        status = TicketCancellationStatus.COMPENSATING;
        errorMessage = null;
        updatedAt = now;
    }

    public void recordCardOperation(
            UUID paymentId,
            UUID operationId,
            String operationStatus,
            Instant now) {
        cardOperations.put(
                paymentId.toString(),
                operationId + ":" + required(operationStatus, "operationStatus"));
        updatedAt = now;
    }

    public void ready(Instant now) {
        status = TicketCancellationStatus.READY;
        errorMessage = null;
        updatedAt = now;
    }

    public void reviewRequired(String message, Instant now) {
        status = TicketCancellationStatus.REVIEW_REQUIRED;
        errorMessage = required(message, "mensaje");
        updatedAt = now;
    }

    public void failed(String message, Instant now) {
        status = TicketCancellationStatus.FAILED;
        errorMessage = required(message, "mensaje");
        updatedAt = now;
    }

    public void complete(Instant now) {
        status = TicketCancellationStatus.COMPLETED;
        errorMessage = null;
        completedAt = now;
        updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTicketId() {
        return ticketId;
    }

    public UUID getStoreId() {
        return storeId;
    }

    public UUID getTerminalId() {
        return terminalId;
    }

    public UUID getOperatorUserId() {
        return operatorUserId;
    }

    public UUID getAuthorizerUserId() {
        return authorizerUserId;
    }

    public String getReason() {
        return reason;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public TicketCancellationStatus getStatus() {
        return status;
    }

    public Map<String, String> getManualCompensations() {
        return Map.copyOf(manualCompensations);
    }

    public Map<String, String> getCardOperations() {
        return Map.copyOf(cardOperations);
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " es obligatorio");
        }
        return value.trim();
    }
}
