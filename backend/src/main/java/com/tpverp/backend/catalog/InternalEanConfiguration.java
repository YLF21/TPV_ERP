package com.tpverp.backend.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "configuracion_ean_interno")
public class InternalEanConfiguration {

    @Id
    @Column(name = "empresa_id")
    private UUID companyId;

    @Column(name = "codigo_empresa", nullable = false, length = 2)
    private String companyCode;

    @Column(name = "config_version", nullable = false)
    private long configVersion;

    @Column(name = "creada_en", nullable = false)
    private Instant createdAt;

    @Column(name = "actualizada_en", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    protected InternalEanConfiguration() {
    }

    public InternalEanConfiguration(UUID companyId, String companyCode, Instant now) {
        this.companyId = Objects.requireNonNull(companyId, "companyId");
        this.companyCode = companyCode(companyCode);
        this.createdAt = Objects.requireNonNull(now, "now");
        this.updatedAt = now;
    }

    public void update(String companyCode, Instant now) {
        this.companyCode = companyCode(companyCode);
        this.updatedAt = Objects.requireNonNull(now, "now");
        this.configVersion++;
    }

    public UUID getCompanyId() { return companyId; }
    public String getCompanyCode() { return companyCode; }
    public long getConfigVersion() { return configVersion; }

    private static String companyCode(String value) {
        var normalized = value == null ? "" : value.trim();
        if (!normalized.matches("[0-9]{2}")) {
            throw new IllegalArgumentException("internal_ean_company_code_invalid");
        }
        return normalized;
    }
}
