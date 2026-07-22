package com.tpverp.saas.license;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

@Entity
@Table(name = "verifactu_activation_policy")
public class VerifactuActivationPolicy {

    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "taxpayer_type", length = 32)
    private TaxpayerType taxpayerType;

    @Column(name = "activation_date", nullable = false)
    private LocalDate activationDate;

    @Version
    private long version;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by", nullable = false, length = 80)
    private String updatedBy;

    @Column(nullable = false, columnDefinition = "text")
    private String reason;

    protected VerifactuActivationPolicy() {
    }

    public TaxpayerType getTaxpayerType() {
        return taxpayerType;
    }

    public LocalDate getActivationDate() {
        return activationDate;
    }

    public long getVersion() {
        return version;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }

    public String getReason() {
        return reason;
    }

    public void update(LocalDate activationDate, Instant updatedAt, String updatedBy, String reason) {
        this.activationDate = Objects.requireNonNull(activationDate, "activationDate");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        this.updatedBy = required(updatedBy, "updatedBy", 80);
        this.reason = required(reason, "reason", 500);
    }

    private static String required(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " es obligatorio");
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(field + " supera la longitud permitida");
        }
        return normalized;
    }
}
