package com.tpverp.saas.license;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "saas_pairing_code")
public class SaasPairingCode {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "company_id", nullable = false)
    private SaasCompany company;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false)
    private SaasStore store;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "license_id", nullable = false)
    private SaasLicense license;

    @Column(nullable = false, unique = true)
    private String code;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected SaasPairingCode() {
    }

    public SaasPairingCode(
            UUID id,
            SaasCompany company,
            SaasStore store,
            SaasLicense license,
            String code,
            Instant expiresAt,
            Instant createdAt) {
        this.id = id;
        this.company = company;
        this.store = store;
        this.license = license;
        this.code = code;
        this.expiresAt = expiresAt;
        this.createdAt = createdAt;
    }

    public SaasCompany getCompany() {
        return company;
    }

    public SaasStore getStore() {
        return store;
    }

    public SaasLicense getLicense() {
        return license;
    }

    public String getCode() {
        return code;
    }

    public Instant getConsumedAt() {
        return consumedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public boolean usableAt(Instant now) {
        return consumedAt == null && expiresAt.isAfter(now);
    }

    public void consume(Instant now) {
        consumedAt = now;
    }

    public void expire(Instant now) {
        expiresAt = now;
    }
}
