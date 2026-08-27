package com.tpverp.saas.license;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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

    @Column(name = "link_recovery_token_hash", length = 64)
    private String linkRecoveryTokenHash;

    @Column(name = "current_pairing_code_id")
    private UUID currentPairingCodeId;

    @Column(name = "linked_at", nullable = false)
    private Instant linkedAt;

    @Column(name = "last_validated_at")
    private Instant lastValidatedAt;

    @Column(name = "app_version")
    private String appVersion;

    @Column(name = "operating_system")
    private String operatingSystem;

    @Column(name = "terminal_name")
    private String terminalName;

    @Column(name = "last_ip")
    private String lastIp;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revoked_by")
    private String revokedBy;

    @Column(name = "revocation_reason")
    private String revocationReason;

    @Version
    @Column(nullable = false)
    private long version;

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
        this(id, company, store, license, installationId, installationReference,
                installationPublicKey, tokenHash, null, linkedAt);
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
            String linkRecoveryTokenHash,
            Instant linkedAt) {
        this.id = id;
        this.company = company;
        this.store = store;
        this.license = license;
        this.installationId = installationId;
        this.installationReference = installationReference;
        this.installationPublicKey = installationPublicKey;
        this.tokenHash = tokenHash;
        this.linkRecoveryTokenHash = linkRecoveryTokenHash;
        this.linkedAt = linkedAt;
        this.active = true;
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

    public String getAppVersion() {
        return appVersion;
    }

    public String getOperatingSystem() {
        return operatingSystem;
    }

    public String getTerminalName() {
        return terminalName;
    }

    public String getLastIp() {
        return lastIp;
    }

    public boolean isActive() {
        return active;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public String getRevokedBy() {
        return revokedBy;
    }

    public String getRevocationReason() {
        return revocationReason;
    }

    public long getVersion() {
        return version;
    }

    public void revoke(Instant now, String actor, String reason) {
        if (!active) {
            return;
        }
        if (now == null || actor == null || actor.isBlank() || reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("La revocacion exige fecha, actor y motivo");
        }
        active = false;
        revokedAt = now;
        revokedBy = actor.trim();
        revocationReason = reason.trim();
    }

    public boolean hasTokenHash(String value) {
        return constantTimeEquals(tokenHash, value);
    }

    public boolean hasLinkRecoveryTokenHash(String value) {
        return constantTimeEquals(linkRecoveryTokenHash, value);
    }

    public boolean isCurrentPairing(UUID pairingCodeId) {
        return currentPairingCodeId == null || currentPairingCodeId.equals(pairingCodeId);
    }

    public void usePairing(UUID pairingCodeId) {
        if (!active) {
            throw new IllegalStateException("La instalacion esta revocada");
        }
        if (pairingCodeId == null) {
            throw new IllegalArgumentException("pairingCodeId es obligatorio");
        }
        currentPairingCodeId = pairingCodeId;
    }

    String tokenHashSnapshot() {
        return tokenHash;
    }

    public void rotateTokenHash(String value) {
        if (!active) {
            throw new IllegalStateException("La instalacion esta revocada");
        }
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("tokenHash es obligatorio");
        }
        tokenHash = value;
    }

    public void validatedAt(Instant now) {
        lastValidatedAt = now;
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        return expected != null
                && actual != null
                && MessageDigest.isEqual(
                        expected.getBytes(StandardCharsets.US_ASCII),
                        actual.getBytes(StandardCharsets.US_ASCII));
    }
}
