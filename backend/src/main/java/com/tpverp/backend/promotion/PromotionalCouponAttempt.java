package com.tpverp.backend.promotion;

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
@Table(name = "cupon_promocional_intento")
public class PromotionalCouponAttempt {

    @Id
    private UUID id;
    @Column(name = "empresa_id", nullable = false)
    private UUID empresaId;
    @Column(name = "tienda_id", nullable = false)
    private UUID tiendaId;
    @Column(name = "usuario_id")
    private UUID usuarioId;
    @Column(name = "terminal_id")
    private UUID terminalId;
    @Column(name = "codigo_hash", length = 128)
    private String codigoHash;
    @Column(name = "codigo_ultimos4", length = 4)
    private String codigoUltimos4;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 48)
    private CouponRejectReason motivo;
    @Column(name = "creado_en", nullable = false)
    private Instant creadoEn;
    @Version
    private long version;

    protected PromotionalCouponAttempt() {
    }

    public PromotionalCouponAttempt(
            UUID companyId,
            UUID storeId,
            UUID userId,
            UUID terminalId,
            String codeHash,
            String codeLast4,
            CouponRejectReason reason,
            Instant createdAt) {
        id = UUID.randomUUID();
        empresaId = Objects.requireNonNull(companyId, "companyId");
        tiendaId = Objects.requireNonNull(storeId, "storeId");
        usuarioId = userId;
        this.terminalId = terminalId;
        codigoHash = optional(codeHash);
        codigoUltimos4 = optionalLast4(codeLast4);
        motivo = Objects.requireNonNull(reason, "reason");
        creadoEn = Objects.requireNonNull(createdAt, "createdAt");
    }

    public UUID id() {
        return id;
    }

    public UUID companyId() {
        return empresaId;
    }

    public UUID storeId() {
        return tiendaId;
    }

    public CouponRejectReason reason() {
        return motivo;
    }

    public Instant createdAt() {
        return creadoEn;
    }

    private static String optional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String optionalLast4(String value) {
        var normalized = optional(value);
        if (normalized != null && normalized.length() != 4) {
            throw new IllegalArgumentException("message.coupon.last4_length");
        }
        return normalized;
    }
}
