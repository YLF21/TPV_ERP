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
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "vale_evento", uniqueConstraints = @UniqueConstraint(
        columnNames = {"vale_id", "documento_id", "tipo"}))
public class VoucherEvent {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "vale_id", nullable = false)
    private Voucher voucher;

    @Column(name = "documento_id", nullable = false)
    private UUID documentId;

    @Column(name = "tienda_id", nullable = false)
    private UUID storeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "tipo", nullable = false, length = 24)
    private VoucherEventType type;

    @Column(name = "importe", nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "usuario_id", nullable = false)
    private UUID userId;

    @Column(name = "ocurrido_en", nullable = false)
    private Instant occurredAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "detalle", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> detail;

    protected VoucherEvent() {
    }

    public VoucherEvent(
            Voucher voucher,
            CommercialDocument document,
            VoucherEventType type,
            BigDecimal amount,
            UUID userId,
            Instant occurredAt,
            Map<String, Object> detail) {
        id = UUID.randomUUID();
        this.voucher = Objects.requireNonNull(voucher, "voucher");
        documentId = Objects.requireNonNull(document, "document").getId();
        storeId = document.getTiendaId();
        this.type = Objects.requireNonNull(type, "type");
        this.amount = Money.euros(amount);
        this.userId = Objects.requireNonNull(userId, "userId");
        this.occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
        this.detail = new LinkedHashMap<>(detail == null ? Map.of() : detail);
    }

    public Voucher getVoucher() {
        return voucher;
    }

    public VoucherEventType getType() {
        return type;
    }

    public BigDecimal getAmount() {
        return amount;
    }
}
