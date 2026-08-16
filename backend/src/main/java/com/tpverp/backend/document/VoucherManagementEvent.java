package com.tpverp.backend.document;

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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "vale_gestion_evento")
public class VoucherManagementEvent {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "vale_id", nullable = false)
    private Voucher voucher;

    @Column(name = "tienda_id", nullable = false)
    private UUID storeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "tipo", nullable = false, length = 32)
    private VoucherManagementEventType type;

    @Column(name = "usuario_id", nullable = false)
    private UUID userId;

    @Column(name = "terminal_id")
    private UUID terminalId;

    @Column(name = "ocurrido_en", nullable = false)
    private Instant occurredAt;

    @Column(name = "motivo", length = 500)
    private String reason;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "detalle", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> detail;

    protected VoucherManagementEvent() {
    }

    public VoucherManagementEvent(
            Voucher voucher,
            VoucherManagementEventType type,
            UUID userId,
            UUID terminalId,
            Instant occurredAt,
            String reason,
            Map<String, Object> detail) {
        id = UUID.randomUUID();
        this.voucher = Objects.requireNonNull(voucher, "voucher");
        storeId = voucher.storeId();
        this.type = Objects.requireNonNull(type, "type");
        this.userId = Objects.requireNonNull(userId, "userId");
        this.terminalId = terminalId;
        this.occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
        this.reason = reason == null ? null : reason.trim();
        this.detail = new LinkedHashMap<>(detail == null ? Map.of() : detail);
    }

    public VoucherManagementEventType type() {
        return type;
    }

    public UUID userId() {
        return userId;
    }

    public UUID terminalId() {
        return terminalId;
    }

    public Instant occurredAt() {
        return occurredAt;
    }

    public String reason() {
        return reason;
    }

    public Map<String, Object> detail() {
        return Map.copyOf(detail);
    }
}
