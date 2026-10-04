package com.tpverp.backend.terminal;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** A physical enrollment. A retired enrollment can never become active again. */
@Entity
@Table(name = "terminal_physical_binding")
public class TerminalPhysicalBinding {
    @Id private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "terminal_id", nullable = false)
    private Terminal terminal;
    @Column(name = "request_id", nullable = false, unique = true) private UUID requestId;
    @Column(name = "device_id", nullable = false) private UUID deviceId;
    @Column(name = "credential_hash", nullable = false) private String credentialHash;
    @Column(nullable = false, length = 128) private String name;
    @Column(name = "device_name", nullable = false, length = 128) private String deviceName;
    @Column(nullable = false, length = 16) private String status;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "expires_at") private Instant expiresAt;
    @Column(name = "approved_at") private Instant approvedAt;
    @Column(name = "ended_at") private Instant endedAt;
    @Column(name = "last_seen_at") private Instant lastSeenAt;
    @Column(name = "legacy_adoption_pending", nullable = false) private boolean legacyAdoptionPending;
    @Version private long version;

    protected TerminalPhysicalBinding() { }
    TerminalPhysicalBinding(Terminal terminal, UUID requestId, UUID deviceId, String credentialHash,
            String name, String deviceName, Instant now, Instant expiresAt) {
        id = UUID.randomUUID(); this.terminal = terminal; this.requestId = requestId;
        this.deviceId = deviceId; this.credentialHash = credentialHash; this.name = name;
        this.deviceName = deviceName; createdAt = now; this.expiresAt = expiresAt; status = "PENDING";
    }
    void approve(Instant now) {
        if (!status.equals("PENDING") && !status.equals("DISABLED") && !status.equals("ACTIVE"))
            throw new IllegalStateException("TERMINAL_BINDING_RETIRED");
        status = "ACTIVE"; if (approvedAt == null) approvedAt = now; expiresAt = null;
    }
    void disable() { status = "DISABLED"; }
    void end(String status, Instant now) { this.status = status; endedAt = now; expiresAt = null; }
    void seen(Instant now) { lastSeenAt = now; }
    void awaitLegacyAdoption() { legacyAdoptionPending = true; }
    void adoptLegacy(UUID requestId, UUID deviceId, String deviceName) {
        this.requestId = requestId; this.deviceId = deviceId; this.deviceName = deviceName;
        legacyAdoptionPending = false;
    }
    public boolean isLegacyAdoptionPending() { return legacyAdoptionPending; }
    public UUID getId() { return id; }
    public Terminal getTerminal() { return terminal; }
    public UUID getRequestId() { return requestId; }
    public UUID getDeviceId() { return deviceId; }
    public String getCredentialHash() { return credentialHash; }
    public String getName() { return name; }
    public String getDeviceName() { return deviceName; }
    public String getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getApprovedAt() { return approvedAt; }
    public Instant getEndedAt() { return endedAt; }
    public Instant getLastSeenAt() { return lastSeenAt; }
    boolean occupies() { return status.equals("PENDING") || status.equals("ACTIVE") || status.equals("DISABLED"); }
}
