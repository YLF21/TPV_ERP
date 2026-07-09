package com.tpverp.backend.promotion;

import com.tpverp.backend.document.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "cupon_promocional", uniqueConstraints = @UniqueConstraint(
        columnNames = {"empresa_id", "codigo_hash"}))
public class PromotionalCoupon {

    @Id
    private UUID id;
    @Column(name = "empresa_id", nullable = false)
    private UUID empresaId;
    @Column(name = "tienda_generado_id", nullable = false)
    private UUID tiendaGeneradoId;
    @Column(name = "tienda_canjeado_id")
    private UUID tiendaCanjeadoId;
    @Column(name = "promocion_id", nullable = false)
    private UUID promocionId;
    @Column(name = "documento_generado_id", nullable = false)
    private UUID documentoGeneradoId;
    @Column(name = "documento_canjeado_id")
    private UUID documentoCanjeadoId;
    @Column(name = "cliente_id")
    private UUID clienteId;
    @Column(name = "member_id")
    private UUID memberId;
    @Column(name = "codigo_hash", nullable = false, length = 128)
    private String codigoHash;
    @Column(name = "codigo_ultimos4", nullable = false, length = 4)
    private String codigoUltimos4;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private PromotionalCouponStatus estado;
    @Enumerated(EnumType.STRING)
    @Column(name = "beneficio_tipo", nullable = false, length = 16)
    private PromotionalCouponBenefitType beneficioTipo;
    @Column(precision = 19, scale = 2)
    private BigDecimal importe;
    @Column(precision = 5, scale = 2)
    private BigDecimal porcentaje;
    @Column(name = "descuento_maximo", precision = 19, scale = 2)
    private BigDecimal descuentoMaximo;
    @Column(name = "minimo_importe", precision = 19, scale = 2)
    private BigDecimal minimoImporte;
    @Column(name = "valido_desde", nullable = false)
    private LocalDate validoDesde;
    @Column(name = "valido_hasta", nullable = false)
    private LocalDate validoHasta;
    @Column(name = "creado_en", nullable = false)
    private Instant creadoEn;
    @Column(name = "usado_en")
    private Instant usadoEn;
    @Column(name = "cancelado_en")
    private Instant canceladoEn;
    @Column(name = "cancelado_por")
    private UUID canceladoPor;
    @Column(name = "motivo_cancelacion", columnDefinition = "text")
    private String motivoCancelacion;
    @Column(name = "reactivado_en")
    private Instant reactivadoEn;
    @Column(name = "reactivado_por")
    private UUID reactivadoPor;
    @Column(name = "motivo_reactivacion", columnDefinition = "text")
    private String motivoReactivacion;
    @Version
    private long version;

    protected PromotionalCoupon() {
    }

    private PromotionalCoupon(
            UUID companyId,
            UUID generatedStoreId,
            UUID promotionId,
            UUID generatedDocumentId,
            String codeHash,
            String codeLast4,
            UUID customerId,
            UUID memberId,
            PromotionalCouponBenefitType benefitType,
            BigDecimal amount,
            BigDecimal percent,
            BigDecimal maximumDiscount,
            BigDecimal minimumAmount,
            LocalDate validFrom,
            LocalDate validUntil,
            Instant createdAt) {
        id = UUID.randomUUID();
        empresaId = Objects.requireNonNull(companyId, "companyId");
        tiendaGeneradoId = Objects.requireNonNull(generatedStoreId, "generatedStoreId");
        promocionId = Objects.requireNonNull(promotionId, "promotionId");
        documentoGeneradoId = Objects.requireNonNull(generatedDocumentId, "generatedDocumentId");
        clienteId = customerId;
        memberId = memberId;
        codigoHash = requiredMax(codeHash, "codigoHash", 128);
        codigoUltimos4 = requiredExactLength(codeLast4, "codigoUltimos4", 4);
        estado = PromotionalCouponStatus.ACTIVE;
        beneficioTipo = Objects.requireNonNull(benefitType, "benefitType");
        if (beneficioTipo == PromotionalCouponBenefitType.AMOUNT) {
            importe = positiveMoney(amount, "importe");
            porcentaje = null;
        } else {
            porcentaje = validPercentage(percent, "porcentaje");
            importe = null;
        }
        descuentoMaximo = maximumDiscount == null ? null : positiveMoney(maximumDiscount, "descuentoMaximo");
        minimoImporte = minimumAmount == null ? null : nonNegativeMoney(minimumAmount, "minimoImporte");
        validoDesde = Objects.requireNonNull(validFrom, "validFrom");
        validoHasta = Objects.requireNonNull(validUntil, "validUntil");
        if (validoHasta.isBefore(validoDesde)) {
            throw new IllegalArgumentException("message.coupon.invalid_dates");
        }
        creadoEn = Objects.requireNonNull(createdAt, "createdAt");
    }

    public static PromotionalCoupon amount(
            UUID companyId,
            UUID generatedStoreId,
            UUID promotionId,
            UUID generatedDocumentId,
            String codeHash,
            String codeLast4,
            BigDecimal amount,
            LocalDate validFrom,
            LocalDate validUntil) {
        return new PromotionalCoupon(
                companyId, generatedStoreId, promotionId, generatedDocumentId,
                codeHash, codeLast4, null, null, PromotionalCouponBenefitType.AMOUNT,
                amount, null, null, null, validFrom, validUntil, Instant.now());
    }

    public static PromotionalCoupon amount(
            UUID companyId,
            UUID generatedStoreId,
            UUID promotionId,
            UUID generatedDocumentId,
            String codeHash,
            String codeLast4,
            UUID customerId,
            UUID memberId,
            BigDecimal amount,
            BigDecimal minimumAmount,
            LocalDate validFrom,
            LocalDate validUntil,
            Instant createdAt) {
        return new PromotionalCoupon(
                companyId, generatedStoreId, promotionId, generatedDocumentId,
                codeHash, codeLast4, customerId, memberId, PromotionalCouponBenefitType.AMOUNT,
                amount, null, null, minimumAmount, validFrom, validUntil, createdAt);
    }

    public static PromotionalCoupon percent(
            UUID companyId,
            UUID generatedStoreId,
            UUID promotionId,
            UUID generatedDocumentId,
            String codeHash,
            String codeLast4,
            UUID customerId,
            UUID memberId,
            BigDecimal percent,
            BigDecimal maximumDiscount,
            BigDecimal minimumAmount,
            LocalDate validFrom,
            LocalDate validUntil,
            Instant createdAt) {
        return new PromotionalCoupon(
                companyId, generatedStoreId, promotionId, generatedDocumentId,
                codeHash, codeLast4, customerId, memberId, PromotionalCouponBenefitType.PERCENT,
                null, percent, maximumDiscount, minimumAmount, validFrom, validUntil, createdAt);
    }

    public UUID id() {
        return id;
    }

    public UUID companyId() {
        return empresaId;
    }

    public UUID generatedStoreId() {
        return tiendaGeneradoId;
    }

    public UUID promotionId() {
        return promocionId;
    }

    public UUID generatedDocumentId() {
        return documentoGeneradoId;
    }

    public UUID customerId() {
        return clienteId;
    }

    public UUID memberId() {
        return memberId;
    }

    public String codeHash() {
        return codigoHash;
    }

    public String codeLast4() {
        return codigoUltimos4;
    }

    public PromotionalCouponStatus status() {
        return estado;
    }

    public PromotionalCouponBenefitType benefitType() {
        return beneficioTipo;
    }

    public BigDecimal amount() {
        return importe;
    }

    public BigDecimal percent() {
        return porcentaje;
    }

    public BigDecimal maximumDiscount() {
        return descuentoMaximo;
    }

    public BigDecimal minimumAmount() {
        return minimoImporte;
    }

    public LocalDate validFrom() {
        return validoDesde;
    }

    public LocalDate validUntil() {
        return validoHasta;
    }

    public UUID redeemedStoreId() {
        return tiendaCanjeadoId;
    }

    public UUID redeemedDocumentId() {
        return documentoCanjeadoId;
    }

    public void use(UUID storeId, UUID documentId, Instant usedAt) {
        if (estado != PromotionalCouponStatus.ACTIVE) {
            throw new IllegalStateException("message.coupon.not_active");
        }
        tiendaCanjeadoId = Objects.requireNonNull(storeId, "storeId");
        documentoCanjeadoId = Objects.requireNonNull(documentId, "documentId");
        usadoEn = Objects.requireNonNull(usedAt, "usedAt");
        estado = PromotionalCouponStatus.USED;
    }

    public void expire(Instant expiredAt) {
        if (estado == PromotionalCouponStatus.ACTIVE) {
            canceladoEn = Objects.requireNonNull(expiredAt, "expiredAt");
            estado = PromotionalCouponStatus.EXPIRED;
        }
    }

    public void cancel(UUID userId, String reason, Instant cancelledAt) {
        if (estado == PromotionalCouponStatus.USED) {
            throw new IllegalStateException("message.coupon.used_cannot_cancel");
        }
        canceladoPor = Objects.requireNonNull(userId, "userId");
        motivoCancelacion = required(reason, "motivoCancelacion");
        canceladoEn = Objects.requireNonNull(cancelledAt, "cancelledAt");
        estado = PromotionalCouponStatus.CANCELLED;
    }

    public void reactivate(UUID userId, String reason, LocalDate currentDate, Instant reactivatedAt) {
        if (estado != PromotionalCouponStatus.CANCELLED) {
            throw new IllegalStateException("message.coupon.only_cancelled_can_reactivate");
        }
        if (Objects.requireNonNull(currentDate, "currentDate").isAfter(validoHasta)) {
            throw new IllegalStateException("message.coupon.expired_cannot_reactivate");
        }
        reactivadoPor = Objects.requireNonNull(userId, "userId");
        motivoReactivacion = required(reason, "motivoReactivacion");
        reactivadoEn = Objects.requireNonNull(reactivatedAt, "reactivatedAt");
        estado = PromotionalCouponStatus.ACTIVE;
    }

    private static BigDecimal positiveMoney(BigDecimal value, String field) {
        var amount = Money.euros(value);
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException(field + " debe ser positivo");
        }
        return amount;
    }

    private static BigDecimal nonNegativeMoney(BigDecimal value, String field) {
        var amount = Money.euros(value);
        if (amount.signum() < 0) {
            throw new IllegalArgumentException(field + " no puede ser negativo");
        }
        return amount;
    }

    private static BigDecimal validPercentage(BigDecimal value, String field) {
        Objects.requireNonNull(value, field);
        var percent = value.setScale(2, java.math.RoundingMode.HALF_UP);
        if (percent.signum() <= 0 || percent.compareTo(new BigDecimal("100")) > 0) {
            throw new IllegalArgumentException(field + " debe estar entre 0 y 100");
        }
        return percent;
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " es obligatorio");
        }
        return value.trim();
    }

    private static String requiredMax(String value, String field, int maxLength) {
        var normalized = required(value, field);
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(field + " no puede superar " + maxLength + " caracteres");
        }
        return normalized;
    }

    private static String requiredExactLength(String value, String field, int length) {
        var normalized = required(value, field);
        if (normalized.length() != length) {
            throw new IllegalArgumentException(field + " debe tener " + length + " caracteres");
        }
        return normalized;
    }
}
