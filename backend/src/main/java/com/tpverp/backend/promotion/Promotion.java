package com.tpverp.backend.promotion;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "promocion")
public class Promotion {

    @Id
    private UUID id;
    @Column(name = "empresa_id", nullable = false)
    private UUID empresaId;
    @Column(name = "version_origen_id")
    private UUID versionOrigenId;
    @Column(nullable = false, length = 160)
    private String nombre;
    @Column(columnDefinition = "text")
    private String descripcion;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private PromotionType tipo;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private PromotionStatus estado;
    @Enumerated(EnumType.STRING)
    @Column(name = "segmento_cliente", nullable = false, length = 32)
    private PromotionCustomerSegment segmentoCliente;
    @Column(name = "member_category_id")
    private UUID memberCategoryId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private PromotionScope ambito;
    @Column(name = "fecha_inicio", nullable = false)
    private LocalDate fechaInicio;
    @Column(name = "fecha_fin")
    private LocalDate fechaFin;
    @Column(name = "minimo_importe", precision = 19, scale = 2)
    private BigDecimal minimoImporte;
    @Column(name = "minimo_cantidad", precision = 19, scale = 3)
    private BigDecimal minimoCantidad;
    @Column(name = "compra_cantidad", precision = 19, scale = 3)
    private BigDecimal compraCantidad;
    @Column(name = "paga_cantidad", precision = 19, scale = 3)
    private BigDecimal pagaCantidad;
    @Enumerated(EnumType.STRING)
    @Column(name = "modo_agrupacion_compra", nullable = false, length = 24)
    private BuyXPayYMode buyXPayYMode;
    @Column(name = "descuento_importe", precision = 19, scale = 2)
    private BigDecimal descuentoImporte;
    @Column(name = "descuento_porcentaje", precision = 5, scale = 2)
    private BigDecimal descuentoPorcentaje;
    @Column(name = "descuento_maximo", precision = 19, scale = 2)
    private BigDecimal descuentoMaximo;
    @Column(name = "precio_lote", precision = 19, scale = 2)
    private BigDecimal precioLote;
    @Column(name = "genera_cupon", nullable = false)
    private boolean generaCupon;
    @Column(name = "cupon_importe", precision = 19, scale = 2)
    private BigDecimal cuponImporte;
    @Column(name = "cupon_porcentaje", precision = 5, scale = 2)
    private BigDecimal cuponPorcentaje;
    @Column(name = "cupon_descuento_maximo", precision = 19, scale = 2)
    private BigDecimal cuponDescuentoMaximo;
    @Column(name = "cupon_minimo_importe", precision = 19, scale = 2)
    private BigDecimal cuponMinimoImporte;
    @Column(name = "cupon_valido_desde_modo", length = 24)
    private String cuponValidoDesdeModo;
    @Column(name = "cupon_valido_desde_fecha")
    private LocalDate cuponValidoDesdeFecha;
    @Column(name = "cupon_valido_desde_dias")
    private Integer cuponValidoDesdeDias;
    @Column(name = "cupon_valido_hasta_fecha")
    private LocalDate cuponValidoHastaFecha;
    @Column(name = "cupon_valido_dias")
    private Integer cuponValidoDias;
    @Column(nullable = false)
    private boolean usada;
    @Column(name = "creado_en", nullable = false)
    private Instant creadoEn;
    @Column(name = "actualizado_en", nullable = false)
    private Instant actualizadoEn;
    @Version
    private long version;

    protected Promotion() {
    }

    private Promotion(UUID companyId, String name, PromotionType type, LocalDate startDate) {
        id = UUID.randomUUID();
        empresaId = Objects.requireNonNull(companyId, "companyId");
        nombre = requiredMax(name, "nombre", 160);
        tipo = Objects.requireNonNull(type, "type");
        estado = PromotionStatus.DRAFT;
        segmentoCliente = PromotionCustomerSegment.ALL;
        ambito = PromotionScope.SALE;
        buyXPayYMode = BuyXPayYMode.MIXED_TARGETS;
        fechaInicio = Objects.requireNonNull(startDate, "startDate");
        creadoEn = Instant.now();
        actualizadoEn = creadoEn;
    }

    public static Promotion draft(UUID companyId, String name, PromotionType type, LocalDate startDate) {
        return new Promotion(companyId, name, type, startDate);
    }

    public UUID id() {
        return id;
    }

    public UUID empresaId() {
        return empresaId;
    }

    public UUID versionOrigenId() {
        return versionOrigenId;
    }

    public UUID rootVersionId() {
        return versionOrigenId == null ? id : versionOrigenId;
    }

    public String name() {
        return nombre;
    }

    public PromotionType type() {
        return tipo;
    }

    public PromotionScope scope() {
        return ambito;
    }

    public PromotionCustomerSegment customerSegment() {
        return segmentoCliente;
    }

    public LocalDate startDate() {
        return fechaInicio;
    }

    public LocalDate endDate() {
        return fechaFin;
    }

    public BigDecimal buyQuantity() {
        return compraCantidad;
    }

    public BigDecimal payQuantity() {
        return pagaCantidad;
    }

    public BuyXPayYMode buyXPayYMode() {
        return buyXPayYMode;
    }

    public BigDecimal minimumAmount() {
        return minimoImporte;
    }

    public BigDecimal minimumQuantity() {
        return minimoCantidad;
    }

    public BigDecimal discountAmount() {
        return descuentoImporte;
    }

    public BigDecimal discountPercent() {
        return descuentoPorcentaje;
    }

    public BigDecimal maximumDiscount() {
        return descuentoMaximo;
    }

    public BigDecimal packPrice() {
        return precioLote;
    }

    public UUID memberCategoryId() {
        return memberCategoryId;
    }

    public boolean generatesCoupon() {
        return generaCupon;
    }

    public BigDecimal couponAmount() {
        return cuponImporte;
    }

    public BigDecimal couponPercent() {
        return cuponPorcentaje;
    }

    public BigDecimal couponMaximumDiscount() {
        return cuponDescuentoMaximo;
    }

    public BigDecimal couponMinimumAmount() {
        return cuponMinimoImporte;
    }

    public LocalDate couponValidFromDate() {
        return cuponValidoDesdeFecha;
    }

    public Integer couponValidFromDays() {
        return cuponValidoDesdeDias;
    }

    public LocalDate couponValidUntilDate() {
        return cuponValidoHastaFecha;
    }

    public Integer couponValidDays() {
        return cuponValidoDias;
    }

    public PromotionStatus status() {
        return estado;
    }

    public boolean used() {
        return usada;
    }

    public void activate() {
        requireComplete();
        estado = PromotionStatus.ACTIVE;
        touch();
    }

    public void deactivate() {
        estado = PromotionStatus.INACTIVE;
        touch();
    }

    public void markUsed() {
        usada = true;
        touch();
    }

    public void rename(String name) {
        requireNotUsed();
        nombre = requiredMax(name, "nombre", 160);
        touch();
    }

    public void configureManagementFields(
            LocalDate startDate,
            LocalDate endDate,
            PromotionScope scope,
            PromotionCustomerSegment customerSegment,
            UUID memberCategoryId) {
        requireNotUsed();
        fechaInicio = Objects.requireNonNull(startDate, "startDate");
        fechaFin = endDate;
        ambito = scope == null ? PromotionScope.SALE : scope;
        segmentoCliente = customerSegment == null ? PromotionCustomerSegment.ALL : customerSegment;
        this.memberCategoryId = segmentoCliente == PromotionCustomerSegment.MEMBER_CATEGORY
                ? Objects.requireNonNull(memberCategoryId, "memberCategoryId")
                : null;
        touch();
    }

    public Promotion duplicateDraft() {
        var duplicate = Promotion.draft(empresaId, nombre, tipo, fechaInicio);
        duplicate.versionOrigenId = rootVersionId();
        duplicate.descripcion = descripcion;
        duplicate.segmentoCliente = segmentoCliente;
        duplicate.memberCategoryId = memberCategoryId;
        duplicate.ambito = ambito;
        duplicate.fechaFin = fechaFin;
        duplicate.minimoImporte = minimoImporte;
        duplicate.minimoCantidad = minimoCantidad;
        duplicate.compraCantidad = compraCantidad;
        duplicate.pagaCantidad = pagaCantidad;
        duplicate.buyXPayYMode = buyXPayYMode;
        duplicate.descuentoImporte = descuentoImporte;
        duplicate.descuentoPorcentaje = descuentoPorcentaje;
        duplicate.descuentoMaximo = descuentoMaximo;
        duplicate.precioLote = precioLote;
        duplicate.generaCupon = generaCupon;
        duplicate.cuponImporte = cuponImporte;
        duplicate.cuponPorcentaje = cuponPorcentaje;
        duplicate.cuponDescuentoMaximo = cuponDescuentoMaximo;
        duplicate.cuponMinimoImporte = cuponMinimoImporte;
        duplicate.cuponValidoDesdeModo = cuponValidoDesdeModo;
        duplicate.cuponValidoDesdeFecha = cuponValidoDesdeFecha;
        duplicate.cuponValidoDesdeDias = cuponValidoDesdeDias;
        duplicate.cuponValidoHastaFecha = cuponValidoHastaFecha;
        duplicate.cuponValidoDias = cuponValidoDias;
        return duplicate;
    }

    public void configureBuyXPayY(BigDecimal buyQuantity, BigDecimal payQuantity) {
        configureBuyXPayY(buyQuantity, payQuantity, BuyXPayYMode.MIXED_TARGETS);
    }

    public void configureBuyXPayY(
            BigDecimal buyQuantity,
            BigDecimal payQuantity,
            BuyXPayYMode mode) {
        requireNotUsed();
        compraCantidad = positiveQuantity(buyQuantity, "compraCantidad");
        pagaCantidad = nonNegativeQuantity(payQuantity, "pagaCantidad");
        if (pagaCantidad.compareTo(compraCantidad) >= 0) {
            throw new IllegalArgumentException("pagaCantidad debe ser menor que compraCantidad");
        }
        buyXPayYMode = mode == null ? BuyXPayYMode.MIXED_TARGETS : mode;
        touch();
    }

    public void configureSecondUnitPercent(BigDecimal percent) {
        requireNotUsed();
        descuentoPorcentaje = positivePercentage(percent, "descuentoPorcentaje");
        touch();
    }

    public void configurePurchaseThresholdDiscount(
            BigDecimal minimumAmount,
            BigDecimal discountAmount,
            BigDecimal discountPercent,
            BigDecimal maximumDiscount) {
        requireNotUsed();
        minimoImporte = nonNegativeAmount(minimumAmount, "minimoImporte");
        configureDiscount(discountAmount, discountPercent, maximumDiscount);
        touch();
    }

    public void configureFixedPackPrice(BigDecimal quantity, BigDecimal packPrice) {
        requireNotUsed();
        compraCantidad = positiveWholeQuantity(quantity, "compraCantidad");
        precioLote = positiveAmount(packPrice, "precioLote");
        touch();
    }

    public void configureQuantityDiscount(
            BigDecimal minimumQuantity,
            BigDecimal discountAmount,
            BigDecimal discountPercent,
            BigDecimal maximumDiscount) {
        requireNotUsed();
        minimoCantidad = positiveQuantity(minimumQuantity, "minimoCantidad");
        configureDiscount(discountAmount, discountPercent, maximumDiscount);
        touch();
    }

    public void configurePurchaseThresholdCoupon(
            BigDecimal purchaseMinimumAmount,
            BigDecimal amount,
            BigDecimal percent,
            BigDecimal maximumDiscount,
            BigDecimal redemptionMinimumAmount,
            LocalDate validFromDate,
            Integer validFromDays,
            LocalDate validUntilDate,
            Integer validDays) {
        requireNotUsed();
        minimoImporte = nonNegativeAmount(purchaseMinimumAmount, "minimoImporte");
        requireExactlyOne(amount, percent, "cuponImporte", "cuponPorcentaje");
        generaCupon = true;
        cuponImporte = amount == null ? null : positiveAmount(amount, "cuponImporte");
        cuponPorcentaje = percent == null ? null : positivePercentage(percent, "cuponPorcentaje");
        cuponDescuentoMaximo = maximumDiscount == null
                ? null : positiveAmount(maximumDiscount, "cuponDescuentoMaximo");
        cuponMinimoImporte = redemptionMinimumAmount == null
                ? null : nonNegativeAmount(redemptionMinimumAmount, "cuponMinimoImporte");
        configureCouponValidity(validFromDate, validFromDays, validUntilDate, validDays);
        touch();
    }

    public void configureGeneratedAmountCoupon(
            BigDecimal amount,
            BigDecimal minimumAmount,
            LocalDate validFrom,
            LocalDate validUntil) {
        requireNotUsed();
        configurePurchaseThresholdCoupon(
                minimumAmount,
                amount,
                null,
                null,
                minimumAmount,
                validFrom,
                null,
                validUntil,
                null);
    }

    private void requireComplete() {
        requiredMax(nombre, "nombre", 160);
        Objects.requireNonNull(tipo, "tipo");
        Objects.requireNonNull(segmentoCliente, "segmentoCliente");
        Objects.requireNonNull(ambito, "ambito");
        Objects.requireNonNull(fechaInicio, "fechaInicio");
        if (segmentoCliente == PromotionCustomerSegment.MEMBER_CATEGORY) {
            Objects.requireNonNull(memberCategoryId, "memberCategoryId");
        }
        if (fechaFin != null && fechaFin.isBefore(fechaInicio)) {
            throw new IllegalStateException("message.promotion.invalid_dates");
        }
        switch (tipo) {
            case PURCHASE_THRESHOLD_COUPON -> requirePurchaseThresholdCoupon();
            case PURCHASE_THRESHOLD_DISCOUNT -> {
                requireMinimumAmount();
                requireDiscount();
            }
            case BUY_X_PAY_Y -> requireBuyXPayY();
            case SECOND_UNIT_PERCENT -> requirePositivePercent();
            case FIXED_PACK_PRICE -> {
                requireWholeQuantity(compraCantidad, "compraCantidad");
                requirePositive(precioLote, "precioLote");
            }
            case QUANTITY_DISCOUNT -> {
                requirePositive(minimoCantidad, "minimoCantidad");
                requireDiscount();
            }
        }
    }

    private void requirePurchaseThresholdCoupon() {
        requireMinimumAmount();
        if (!generaCupon) {
            throw new IllegalStateException("generaCupon es obligatorio");
        }
        requireExactlyOne(cuponImporte, cuponPorcentaje, "cuponImporte", "cuponPorcentaje");
        if (cuponValidoHastaFecha == null && cuponValidoDias == null) {
            throw new IllegalStateException("la validez final del cupon es obligatoria");
        }
    }

    private void requireBuyXPayY() {
        requireWholeQuantity(compraCantidad, "compraCantidad");
        if (pagaCantidad == null || pagaCantidad.signum() < 0
                || pagaCantidad.stripTrailingZeros().scale() > 0
                || pagaCantidad.compareTo(compraCantidad) >= 0) {
            throw new IllegalStateException("compraCantidad y pagaCantidad son obligatorias");
        }
        Objects.requireNonNull(buyXPayYMode, "buyXPayYMode");
    }

    private void requireMinimumAmount() {
        if (minimoImporte == null || minimoImporte.signum() < 0) {
            throw new IllegalStateException("minimoImporte es obligatorio");
        }
    }

    private void requirePositivePercent() {
        if (descuentoPorcentaje == null || descuentoPorcentaje.signum() <= 0
                || descuentoPorcentaje.compareTo(new BigDecimal("100")) > 0) {
            throw new IllegalStateException("descuentoPorcentaje es obligatorio");
        }
    }

    private void requireDiscount() {
        requireExactlyOne(descuentoImporte, descuentoPorcentaje,
                "descuentoImporte", "descuentoPorcentaje");
    }

    private void configureDiscount(
            BigDecimal amount,
            BigDecimal percent,
            BigDecimal maximumDiscount) {
        requireExactlyOne(amount, percent, "descuentoImporte", "descuentoPorcentaje");
        descuentoImporte = amount == null ? null : positiveAmount(amount, "descuentoImporte");
        descuentoPorcentaje = percent == null ? null : positivePercentage(percent, "descuentoPorcentaje");
        descuentoMaximo = maximumDiscount == null
                ? null : positiveAmount(maximumDiscount, "descuentoMaximo");
    }

    private void configureCouponValidity(
            LocalDate validFromDate,
            Integer validFromDays,
            LocalDate validUntilDate,
            Integer validDays) {
        if (validFromDate != null && validFromDays != null) {
            throw new IllegalArgumentException("solo se permite un inicio de validez del cupon");
        }
        if (validUntilDate != null && validDays != null) {
            throw new IllegalArgumentException("solo se permite un fin de validez del cupon");
        }
        if (validFromDays != null && validFromDays < 0) {
            throw new IllegalArgumentException("cuponValidoDesdeDias no puede ser negativo");
        }
        if (validDays != null && validDays <= 0) {
            throw new IllegalArgumentException("cuponValidoDias debe ser positivo");
        }
        if (validUntilDate != null && validFromDate != null && validUntilDate.isBefore(validFromDate)) {
            throw new IllegalArgumentException("message.coupon.invalid_dates");
        }
        cuponValidoDesdeFecha = validFromDate;
        cuponValidoDesdeDias = validFromDays;
        cuponValidoHastaFecha = validUntilDate;
        cuponValidoDias = validDays;
    }

    private void requireNotUsed() {
        if (usada) {
            throw new IllegalStateException("message.promotion.used_requires_new_version");
        }
    }

    private void touch() {
        actualizadoEn = Instant.now();
    }

    private static String requiredMax(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " es obligatorio");
        }
        var normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(field + " no puede superar " + maxLength + " caracteres");
        }
        return normalized;
    }

    private static BigDecimal positiveQuantity(BigDecimal value, String field) {
        Objects.requireNonNull(value, field);
        if (value.signum() <= 0 || value.stripTrailingZeros().scale() > 3) {
            throw new IllegalArgumentException(field + " debe ser positivo");
        }
        return value.setScale(3, java.math.RoundingMode.HALF_UP);
    }

    private static BigDecimal positiveWholeQuantity(BigDecimal value, String field) {
        var quantity = positiveQuantity(value, field);
        if (quantity.stripTrailingZeros().scale() > 0) {
            throw new IllegalArgumentException(field + " debe ser un entero positivo");
        }
        return quantity;
    }

    private static BigDecimal nonNegativeQuantity(BigDecimal value, String field) {
        Objects.requireNonNull(value, field);
        if (value.signum() < 0 || value.stripTrailingZeros().scale() > 3) {
            throw new IllegalArgumentException(field + " no puede ser negativo");
        }
        return value.setScale(3, java.math.RoundingMode.HALF_UP);
    }

    private static BigDecimal validPercentage(BigDecimal value, String field) {
        Objects.requireNonNull(value, field);
        if (value.signum() < 0 || value.compareTo(new BigDecimal("100")) > 0) {
            throw new IllegalArgumentException(field + " debe estar entre 0 y 100");
        }
        return value.setScale(2, java.math.RoundingMode.HALF_UP);
    }

    private static BigDecimal positivePercentage(BigDecimal value, String field) {
        var percentage = validPercentage(value, field);
        if (percentage.signum() <= 0) {
            throw new IllegalArgumentException(field + " debe ser positivo");
        }
        return percentage;
    }

    private static BigDecimal positiveAmount(BigDecimal value, String field) {
        var amount = nonNegativeAmount(value, field);
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException(field + " debe ser positivo");
        }
        return amount;
    }

    private static BigDecimal nonNegativeAmount(BigDecimal value, String field) {
        Objects.requireNonNull(value, field);
        if (value.signum() < 0) {
            throw new IllegalArgumentException(field + " no puede ser negativo");
        }
        return value.setScale(2, java.math.RoundingMode.HALF_UP);
    }

    private static void requireExactlyOne(
            Object first,
            Object second,
            String firstField,
            String secondField) {
        if ((first == null) == (second == null)) {
            throw new IllegalArgumentException(
                    "se debe indicar exactamente uno entre " + firstField + " y " + secondField);
        }
    }

    private static void requirePositive(BigDecimal value, String field) {
        if (value == null || value.signum() <= 0) {
            throw new IllegalStateException(field + " es obligatorio");
        }
    }

    private static void requireWholeQuantity(BigDecimal value, String field) {
        requirePositive(value, field);
        if (value.stripTrailingZeros().scale() > 0) {
            throw new IllegalStateException(field + " debe ser un entero positivo");
        }
    }
}
