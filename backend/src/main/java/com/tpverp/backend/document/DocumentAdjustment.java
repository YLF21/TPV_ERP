package com.tpverp.backend.document;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "documento_ajuste", uniqueConstraints = @UniqueConstraint(
        columnNames = {"documento_id", "orden"}))
public class DocumentAdjustment {

    @Id
    private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "documento_id", nullable = false)
    private CommercialDocument documento;
    @Column(nullable = false, length = 24)
    private String tipo;
    @Column(nullable = false)
    private int orden;
    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal porcentaje;
    @Column(name = "base_elegible", nullable = false, precision = 19, scale = 2)
    private BigDecimal baseElegible;
    @Column(name = "importe_aplicado", nullable = false, precision = 19, scale = 2)
    private BigDecimal importeAplicado;
    @Column(name = "usuario_id")
    private UUID usuarioId;
    @Column(name = "creado_en", nullable = false)
    private Instant creadoEn;
    @Column(name = "member_id")
    private UUID memberId;
    @Column(name = "member_category_id")
    private UUID memberCategoryId;
    @Column(name = "member_category_name", length = 160)
    private String memberCategoryName;

    protected DocumentAdjustment() {
    }

    public DocumentAdjustment(
            CommercialDocument documento,
            String tipo,
            int orden,
            BigDecimal porcentaje,
            BigDecimal baseElegible,
            BigDecimal importeAplicado,
            UUID usuarioId,
            Instant creadoEn,
            UUID memberId,
            UUID memberCategoryId,
            String memberCategoryName) {
        this.id = UUID.randomUUID();
        this.documento = Objects.requireNonNull(documento, "documento");
        this.tipo = Objects.requireNonNull(tipo, "tipo");
        this.orden = orden;
        this.porcentaje = Money.validPercentage(porcentaje);
        this.baseElegible = Money.euros(Objects.requireNonNull(baseElegible, "baseElegible"));
        this.importeAplicado = Money.euros(Objects.requireNonNull(importeAplicado, "importeAplicado"));
        this.usuarioId = usuarioId;
        this.creadoEn = Objects.requireNonNull(creadoEn, "creadoEn");
        this.memberId = memberId;
        this.memberCategoryId = memberCategoryId;
        this.memberCategoryName = memberCategoryName;
    }

    public UUID getId() { return id; }
    public String getTipo() { return tipo; }
    public int getOrden() { return orden; }
    public BigDecimal getPorcentaje() { return porcentaje; }
    public BigDecimal getBaseElegible() { return baseElegible; }
    public BigDecimal getImporteAplicado() { return importeAplicado; }
    public UUID getUsuarioId() { return usuarioId; }
    public Instant getCreadoEn() { return creadoEn; }
    public UUID getMemberId() { return memberId; }
    public UUID getMemberCategoryId() { return memberCategoryId; }
    public String getMemberCategoryName() { return memberCategoryName; }
}
