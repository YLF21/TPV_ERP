package com.tpverp.backend.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "asignacion_ean_interno")
public class InternalEanAllocation {

    public enum Origin { GENERADO, MANUAL }
    public enum Status { RESERVADO, ASIGNADO }

    @Id
    private UUID id;
    @Column(name = "empresa_id", nullable = false) private UUID companyId;
    @Column(name = "tienda_id", nullable = false) private UUID storeId;
    @Column(name = "producto_id") private UUID productId;
    @Column(name = "operador_id", nullable = false) private UUID operatorId;
    @Column(name = "terminal_id", nullable = false) private UUID terminalId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 8) private InternalEanFormat formato;
    @Column(nullable = false, length = 13) private String codigo;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) private Origin origen;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) private Status estado;
    @Enumerated(EnumType.STRING) @Column(name = "tipo_identificador", length = 32) private IdentifierType identifierType;
    @Column(name = "reservada_en", nullable = false) private Instant reservedAt;
    @Column(name = "expira_en") private Instant expiresAt;
    @Column(name = "asignada_en") private Instant assignedAt;

    protected InternalEanAllocation() {
    }

    public static InternalEanAllocation reservation(
            UUID companyId,
            UUID storeId,
            UUID operatorId,
            UUID terminalId,
            InternalEanFormat format,
            String code,
            Instant now,
            Duration ttl) {
        var value = new InternalEanAllocation();
        value.id = UUID.randomUUID();
        value.companyId = Objects.requireNonNull(companyId, "companyId");
        value.storeId = Objects.requireNonNull(storeId, "storeId");
        value.operatorId = Objects.requireNonNull(operatorId, "operatorId");
        value.terminalId = Objects.requireNonNull(terminalId, "terminalId");
        value.formato = Objects.requireNonNull(format, "format");
        value.codigo = Objects.requireNonNull(code, "code");
        value.origen = Origin.GENERADO;
        value.estado = Status.RESERVADO;
        value.reservedAt = Objects.requireNonNull(now, "now");
        value.expiresAt = now.plus(Objects.requireNonNull(ttl, "ttl"));
        return value;
    }

    public static InternalEanAllocation manualReservation(
            UUID companyId,
            UUID storeId,
            UUID operatorId,
            UUID terminalId,
            InternalEanFormat format,
            String code,
            Instant now,
            Duration ttl) {
        var value = reservation(
                companyId, storeId, operatorId, terminalId, format, code,
                now, ttl);
        value.origen = Origin.MANUAL;
        return value;
    }

    public static InternalEanAllocation manualAssignment(
            UUID companyId,
            UUID storeId,
            UUID productId,
            UUID operatorId,
            UUID terminalId,
            InternalEanFormat format,
            String code,
            IdentifierType identifierType,
            Instant now) {
        var value = reservation(
                companyId, storeId, operatorId, terminalId, format, code, now,
                Duration.ofMinutes(1));
        value.origen = Origin.MANUAL;
        value.assign(productId, identifierType, now);
        return value;
    }

    public void reclaim(UUID operatorId, UUID terminalId, Instant now, Duration ttl) {
        if (estado != Status.RESERVADO || expiresAt == null || expiresAt.isAfter(now)) {
            throw new IllegalStateException("internal_ean_reservation_not_expired");
        }
        this.operatorId = Objects.requireNonNull(operatorId, "operatorId");
        this.terminalId = Objects.requireNonNull(terminalId, "terminalId");
        this.reservedAt = Objects.requireNonNull(now, "now");
        this.expiresAt = now.plus(Objects.requireNonNull(ttl, "ttl"));
    }

    public void requireOwnedActive(
            UUID expectedStoreId,
            UUID expectedOperatorId,
            UUID expectedTerminalId,
            Instant now) {
        if (estado != Status.RESERVADO
                || !storeId.equals(expectedStoreId)
                || !operatorId.equals(expectedOperatorId)
                || !terminalId.equals(expectedTerminalId)
                || expiresAt == null
                || !expiresAt.isAfter(now)) {
            throw new IllegalStateException("internal_ean_reservation_invalid");
        }
    }

    public void requireAssignedOwned(
            UUID expectedStoreId,
            UUID expectedOperatorId,
            UUID expectedTerminalId,
            UUID expectedProductId) {
        if (estado != Status.ASIGNADO
                || !storeId.equals(expectedStoreId)
                || !operatorId.equals(expectedOperatorId)
                || !terminalId.equals(expectedTerminalId)
                || !Objects.equals(productId, expectedProductId)) {
            throw new IllegalStateException("internal_ean_assignment_invalid");
        }
    }

    public void assign(UUID productId, IdentifierType identifierType, Instant now) {
        if (estado != Status.RESERVADO) {
            throw new IllegalStateException("internal_ean_reservation_already_used");
        }
        this.productId = Objects.requireNonNull(productId, "productId");
        this.identifierType = Objects.requireNonNull(identifierType, "identifierType");
        this.estado = Status.ASIGNADO;
        this.expiresAt = null;
        this.assignedAt = Objects.requireNonNull(now, "now");
    }

    public UUID getId() { return id; }
    public UUID getStoreId() { return storeId; }
    public UUID getOperatorId() { return operatorId; }
    public UUID getTerminalId() { return terminalId; }
    public InternalEanFormat getFormat() { return formato; }
    public String getCode() { return codigo; }
    public Instant getExpiresAt() { return expiresAt; }
    public Origin getOrigin() { return origen; }
}
