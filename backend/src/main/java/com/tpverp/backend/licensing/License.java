package com.tpverp.backend.licensing;

import com.tpverp.backend.installation.Installation;
import com.tpverp.backend.licensing.application.TaxRegime;
import com.tpverp.backend.licensing.application.TaxpayerType;
import com.tpverp.backend.organization.Store;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
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
@Table(name = "licencia")
public class License {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tienda_id", nullable = false)
    private Store tienda;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "instalacion_id", nullable = false)
    private Installation instalacion;

    @Column(nullable = false, unique = true, length = 32)
    private String referencia;

    @Column(name = "valida_desde", nullable = false)
    private Instant validaDesde;

    @Column(name = "valida_hasta", nullable = false)
    private Instant validaHasta;

    @Column(name = "max_windows", nullable = false)
    private int maxWindows;

    @Column(name = "max_pda", nullable = false)
    private int maxPda;

    @Column(name = "tax_id", length = 9)
    private String taxId;

    @Enumerated(EnumType.STRING)
    @Column(name = "taxpayer_type", length = 16)
    private TaxpayerType taxpayerType;

    @Enumerated(EnumType.STRING)
    @Column(name = "regimen_impuesto", length = 8)
    private TaxRegime regimenImpuesto;

    @Column(name = "blob_original", nullable = false, columnDefinition = "text")
    private String blobOriginal;

    @Column(nullable = false, length = 128)
    private String hash;

    @Column(name = "format_version", nullable = false)
    private int formatVersion;

    @Column(name = "importada_en", nullable = false)
    private Instant importadaEn;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "import_metadata", columnDefinition = "jsonb")
    private Map<String, Object> metadataImportacion;

    @Enumerated(EnumType.STRING)
    @Column(name = "import_result", nullable = false, length = 32)
    private ImportResult resultadoImportacion;

    @Column(name = "import_reason", columnDefinition = "text")
    private String motivoImportacion;

    @Column(nullable = false)
    private boolean activa;

    @Version
    private long version;

    protected License() {
    }

    public License(
            Store tienda,
            Installation instalacion,
            String referencia,
            Instant validaDesde,
            Instant validaHasta,
            int maxWindows,
            int maxPda,
            String taxId,
            TaxpayerType taxpayerType,
            TaxRegime regimenImpuesto,
            String blobOriginal,
            String hash,
            int formatVersion,
            Instant importadaEn,
            Map<String, Object> metadataImportacion,
            ImportResult resultadoImportacion,
            String motivoImportacion,
            boolean activa) {
        if (maxWindows < 1 || maxPda < 0 || formatVersion < 1) {
            throw new IllegalArgumentException("Limites o version de formato invalidos");
        }
        if (!Objects.requireNonNull(validaHasta, "validaHasta").isAfter(
                Objects.requireNonNull(validaDesde, "validaDesde"))) {
            throw new IllegalArgumentException("validaHasta debe ser posterior a validaDesde");
        }
        if (resultadoImportacion == ImportResult.RECHAZADA
                && (motivoImportacion == null || motivoImportacion.isBlank())) {
            throw new IllegalArgumentException("Una importacion rechazada requiere motivo");
        }
        this.id = UUID.randomUUID();
        this.tienda = Objects.requireNonNull(tienda, "tienda");
        this.instalacion = Objects.requireNonNull(instalacion, "instalacion");
        this.referencia = required(referencia, "referencia");
        this.validaDesde = validaDesde;
        this.validaHasta = validaHasta;
        this.maxWindows = maxWindows;
        this.maxPda = maxPda;
        this.taxId = required(taxId, "taxId");
        this.taxpayerType = Objects.requireNonNull(taxpayerType, "taxpayerType");
        this.regimenImpuesto = Objects.requireNonNull(regimenImpuesto, "regimenImpuesto");
        this.blobOriginal = required(blobOriginal, "blobOriginal");
        this.hash = required(hash, "hash");
        this.formatVersion = formatVersion;
        this.importadaEn = Objects.requireNonNull(importadaEn, "importadaEn");
        this.metadataImportacion = metadataImportacion == null ? null : new LinkedHashMap<>(metadataImportacion);
        this.resultadoImportacion = Objects.requireNonNull(resultadoImportacion, "resultadoImportacion");
        this.motivoImportacion = resultadoImportacion == ImportResult.ACEPTADA ? null : motivoImportacion.trim();
        this.activa = activa;
    }

    public UUID getId() {
        return id;
    }

    public String getReferencia() {
        return referencia;
    }

    public Instant getValidaDesde() {
        return validaDesde;
    }

    public Instant getValidaHasta() {
        return validaHasta;
    }

    public int getMaxWindows() {
        return maxWindows;
    }

    public int getMaxPda() {
        return maxPda;
    }

    public TaxRegime getRegimenImpuesto() {
        return regimenImpuesto;
    }

    public String getTaxId() {
        return taxId;
    }

    public TaxpayerType getTaxpayerType() {
        return taxpayerType;
    }

    public int getFormatVersion() {
        return formatVersion;
    }

    public boolean isActiva() {
        return activa;
    }

    public void deactivate() {
        activa = false;
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " es obligatorio");
        }
        return value.trim();
    }
}
