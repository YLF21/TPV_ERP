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
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
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

    @Column(name = "ultima_validacion_saas", nullable = false)
    private Instant ultimaValidacionSaas;

    @Column(name = "verifactu_activation_date")
    private LocalDate verifactuActivationDate;

    @Column(name = "verifactu_policy_version")
    private Long verifactuPolicyVersion;

    @Column(name = "verifactu_policy_updated_at")
    private Instant verifactuPolicyUpdatedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "estado_saas", nullable = false, length = 32)
    private LicenseSaasStatus estadoSaas = LicenseSaasStatus.VALIDA;

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
        this.ultimaValidacionSaas = this.importadaEn;
        this.estadoSaas = LicenseSaasStatus.VALIDA;
        this.metadataImportacion = metadataImportacion == null ? null : new LinkedHashMap<>(metadataImportacion);
        this.resultadoImportacion = Objects.requireNonNull(resultadoImportacion, "resultadoImportacion");
        this.motivoImportacion = resultadoImportacion == ImportResult.ACEPTADA ? null : motivoImportacion.trim();
        this.activa = activa;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTiendaId() {
        return tienda.getId();
    }

    public UUID getInstalacionId() {
        return instalacion.getId();
    }

    public String getInstalacionReferencia() {
        return instalacion.getReferencia();
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

    public String getHash() {
        return hash;
    }

    public boolean isActiva() {
        return activa;
    }

    public Instant getUltimaValidacionSaas() {
        return ultimaValidacionSaas;
    }

    public LicenseSaasStatus getEstadoSaas() {
        return estadoSaas;
    }

    public LocalDate getVerifactuActivationDate() {
        return verifactuActivationDate;
    }

    public Long getVerifactuPolicyVersion() {
        return verifactuPolicyVersion;
    }

    public Instant getVerifactuPolicyUpdatedAt() {
        return verifactuPolicyUpdatedAt;
    }

    public boolean applyVerifactuPolicy(
            LocalDate activationDate,
            long policyVersion,
            Instant policyUpdatedAt) {
        Objects.requireNonNull(activationDate, "activationDate");
        Objects.requireNonNull(policyUpdatedAt, "policyUpdatedAt");
        if (policyVersion < 0) {
            throw new IllegalArgumentException("Version de politica VERI*FACTU invalida");
        }
        if (verifactuPolicyVersion != null && policyVersion < verifactuPolicyVersion) {
            return false;
        }
        if (verifactuPolicyVersion != null
                && policyVersion == verifactuPolicyVersion
                && (!activationDate.equals(verifactuActivationDate)
                        || !policyUpdatedAt.equals(verifactuPolicyUpdatedAt))) {
            throw new IllegalStateException(
                    "La misma version de politica VERI*FACTU contiene datos distintos");
        }
        if (verifactuPolicyVersion != null && policyVersion == verifactuPolicyVersion) {
            return false;
        }
        verifactuActivationDate = activationDate;
        verifactuPolicyVersion = policyVersion;
        verifactuPolicyUpdatedAt = policyUpdatedAt;
        return true;
    }

    public void markSaasValidated(Instant validatedAt, Instant validUntil) {
        ultimaValidacionSaas = Objects.requireNonNull(validatedAt, "validatedAt");
        validaHasta = Objects.requireNonNull(validUntil, "validUntil");
        estadoSaas = LicenseSaasStatus.VALIDA;
    }

    public void markSaasBlocked(Instant validatedAt) {
        ultimaValidacionSaas = Objects.requireNonNull(validatedAt, "validatedAt");
        estadoSaas = LicenseSaasStatus.BLOQUEADA_MANUAL;
    }

    public void markSaasRejected(Instant validatedAt, LicenseSaasStatus status, Instant validUntil) {
        ultimaValidacionSaas = Objects.requireNonNull(validatedAt, "validatedAt");
        estadoSaas = Objects.requireNonNull(status, "status");
        if (validUntil != null) {
            validaHasta = validUntil;
        }
    }
    // Stores the exact SaaS rejection state so support can distinguish expired, blocked and update-required licenses.

    public boolean isOperationalAt(Instant now) {
        Objects.requireNonNull(now, "now");
        if (estadoSaas != LicenseSaasStatus.VALIDA) {
            return false;
        }
        if (now.isBefore(validaDesde)) {
            return false;
        }
        boolean expired = !now.isBefore(validaHasta);
        boolean missingSaasValidationForMoreThanOneMonth =
                ultimaValidacionSaas.plus(30, ChronoUnit.DAYS).isBefore(now);
        return !expired || !missingSaasValidationForMoreThanOneMonth;
    }

    public boolean requiresOfflineExpiredWarningAt(Instant now) {
        Objects.requireNonNull(now, "now");
        boolean expired = !now.isBefore(validaHasta);
        boolean missingSaasValidationForOneWeek =
                !now.isBefore(ultimaValidacionSaas.plus(7, ChronoUnit.DAYS));
        return expired && missingSaasValidationForOneWeek;
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
