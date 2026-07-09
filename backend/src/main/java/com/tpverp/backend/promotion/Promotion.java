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

    public String name() {
        return nombre;
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

    private void requireComplete() {
        requiredMax(nombre, "nombre", 160);
        Objects.requireNonNull(tipo, "tipo");
        Objects.requireNonNull(segmentoCliente, "segmentoCliente");
        Objects.requireNonNull(ambito, "ambito");
        Objects.requireNonNull(fechaInicio, "fechaInicio");
        if (fechaFin != null && fechaFin.isBefore(fechaInicio)) {
            throw new IllegalStateException("message.promotion.invalid_dates");
        }
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
}
