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

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "consumed_installation_id")
    private SaasInstallation consumedInstallation;

    @Column(name = "link_recovery_token_hash", length = 64)
    private String linkRecoveryTokenHash;

    @Column(name = "previous_installation_token_hash", length = 64)
    private String previousInstallationTokenHash;

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
        this.code = LicenseProvisioningData.requiredCode(code, "code");
        this.expiresAt = expiresAt;
        this.createdAt = createdAt;
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

    public String getCode() {
        return code;
    }

    public Instant getConsumedAt() {
        return consumedAt;
    }

    public SaasInstallation getConsumedInstallation() {
        return consumedInstallation;
    }

    public boolean hasLinkRecoveryTokenHash(String value) {
        return constantTimeEquals(linkRecoveryTokenHash, value);
    }

    public boolean hasPreviousInstallationTokenHash(String value) {
        return constantTimeEquals(previousInstallationTokenHash, value);
    }

    public boolean hasRetryCredentialContext() {
        return linkRecoveryTokenHash != null || previousInstallationTokenHash != null;
    }

    public boolean hasLinkRecoveryCredentialContext() {
        return linkRecoveryTokenHash != null;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public boolean usableAt(Instant now) {
        return consumedAt == null && expiresAt.isAfter(now);
    }

    public void consume(Instant now, SaasInstallation installation) {
        consume(now, installation, null, null);
    }

    public void consume(
            Instant now,
            SaasInstallation installation,
            String recoveryTokenHash,
            String previousTokenHash) {
        if (installation == null) {
            throw new IllegalArgumentException("La instalacion consumidora es obligatoria");
        }
        if (consumedAt != null || consumedInstallation != null) {
            throw new IllegalStateException("El codigo de enlace ya esta consumido");
        }
        consumedAt = now;
        consumedInstallation = installation;
        linkRecoveryTokenHash = recoveryTokenHash;
        previousInstallationTokenHash = previousTokenHash;
    }

    public void rememberPreviousInstallationTokenHash(String value) {
        if (previousInstallationTokenHash != null) {
            return;
        }
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("previousInstallationTokenHash es obligatorio");
        }
        previousInstallationTokenHash = value;
    }

    public void expire(Instant now) {
        expiresAt = now;
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        return expected != null
                && actual != null
                && java.security.MessageDigest.isEqual(
                        expected.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                        actual.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }
}
