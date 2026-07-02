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
@Table(name = "saas_installation")
public class SaasInstallation {

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

    @Column(name = "installation_id", nullable = false, unique = true)
    private UUID installationId;

    @Column(name = "installation_reference", nullable = false)
    private String installationReference;

    @Column(name = "installation_public_key")
    private String installationPublicKey;

    @Column(name = "token_hash", nullable = false)
    private String tokenHash;

    @Column(name = "linked_at", nullable = false)
    private Instant linkedAt;

    @Column(name = "last_validated_at")
    private Instant lastValidatedAt;

    protected SaasInstallation() {
    }

    public SaasInstallation(
            UUID id,
            SaasCompany company,
            SaasStore store,
            SaasLicense license,
            UUID installationId,
            String installationReference,
            String installationPublicKey,
            String tokenHash,
            Instant linkedAt) {
        this.id = id;
        this.company = company;
        this.store = store;
        this.license = license;
        this.installationId = installationId;
        this.installationReference = installationReference;
        this.installationPublicKey = installationPublicKey;
        this.tokenHash = tokenHash;
        this.linkedAt = linkedAt;
    }

    public UUID getId() {
        return id;
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

    public UUID getInstallationId() {
        return installationId;
    }

    public String getInstallationReference() {
        return installationReference;
    }

    public Instant getLinkedAt() {
        return linkedAt;
    }

    public Instant getLastValidatedAt() {
        return lastValidatedAt;
    }

    public boolean hasTokenHash(String value) {
        return tokenHash.equals(value);
    }

    public void validatedAt(Instant now) {
        lastValidatedAt = now;
    }
}
