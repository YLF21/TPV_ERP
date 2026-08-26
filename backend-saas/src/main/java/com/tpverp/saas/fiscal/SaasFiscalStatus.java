package com.tpverp.saas.fiscal;

import com.tpverp.saas.license.SaasCompany;
import com.tpverp.saas.license.SaasInstallation;
import com.tpverp.saas.license.SaasStore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "saas_fiscal_status")
public class SaasFiscalStatus {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "installation_id", nullable = false, unique = true)
    private SaasInstallation installation;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "company_id", nullable = false)
    private SaasCompany company;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false)
    private SaasStore store;

    @Column(name = "source_installation_id", nullable = false)
    private UUID sourceInstallationId;

    @Column(name = "entity_id", nullable = false)
    private UUID entityId;

    @Column(name = "effective_mode", nullable = false, length = 32)
    private String effectiveMode;

    @Column(name = "activation_state", nullable = false, length = 32)
    private String activationState;

    @Column(name = "mode_version", nullable = false)
    private long modeVersion;

    @Column(name = "mode_since")
    private Instant modeSince;

    @Column(name = "activation_date")
    private LocalDate activationDate;

    @Column(name = "policy_version")
    private Long policyVersion;

    @Column(name = "runtime_class", nullable = false, length = 16)
    private String runtimeClass;

    @Column(name = "endpoint_environment", nullable = false, length = 16)
    private String endpointEnvironment;

    @Column(name = "transport_mode", nullable = false, length = 16)
    private String transportMode;

    @Column(name = "reported_at", nullable = false)
    private Instant reportedAt;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(name = "payload_hash", nullable = false, length = 64)
    private String payloadHash;

    protected SaasFiscalStatus() {
    }

    public SaasFiscalStatus(UUID id, SaasInstallation installation, SaasCompany company,
            SaasStore store, UUID sourceInstallationId, UUID entityId, String effectiveMode,
            String activationState, long modeVersion, Instant modeSince, LocalDate activationDate,
            Long policyVersion, String runtimeClass, String endpointEnvironment,
            String transportMode, Instant reportedAt, Instant receivedAt, String payloadHash) {
        this.id = id;
        this.installation = installation;
        this.company = company;
        this.store = store;
        this.sourceInstallationId = sourceInstallationId;
        this.entityId = entityId;
        this.effectiveMode = effectiveMode;
        this.activationState = activationState;
        this.modeVersion = modeVersion;
        this.modeSince = modeSince;
        this.activationDate = activationDate;
        this.policyVersion = policyVersion;
        this.runtimeClass = runtimeClass;
        this.endpointEnvironment = endpointEnvironment;
        this.transportMode = transportMode;
        this.reportedAt = reportedAt;
        this.receivedAt = receivedAt;
        this.payloadHash = payloadHash;
    }

    public void update(String effectiveMode, String activationState, long modeVersion,
            Instant modeSince, LocalDate activationDate, Long policyVersion, String runtimeClass,
            String endpointEnvironment, String transportMode, Instant reportedAt,
            Instant receivedAt, String payloadHash) {
        this.effectiveMode = effectiveMode;
        this.activationState = activationState;
        this.modeVersion = modeVersion;
        this.modeSince = modeSince;
        this.activationDate = activationDate;
        this.policyVersion = policyVersion;
        this.runtimeClass = runtimeClass;
        this.endpointEnvironment = endpointEnvironment;
        this.transportMode = transportMode;
        this.reportedAt = reportedAt;
        this.receivedAt = receivedAt;
        this.payloadHash = payloadHash;
    }

    public UUID getId() { return id; }
    public SaasInstallation getInstallation() { return installation; }
    public SaasCompany getCompany() { return company; }
    public SaasStore getStore() { return store; }
    public UUID getSourceInstallationId() { return sourceInstallationId; }
    public UUID getEntityId() { return entityId; }
    public String getEffectiveMode() { return effectiveMode; }
    public String getActivationState() { return activationState; }
    public long getModeVersion() { return modeVersion; }
    public Instant getModeSince() { return modeSince; }
    public LocalDate getActivationDate() { return activationDate; }
    public Long getPolicyVersion() { return policyVersion; }
    public String getRuntimeClass() { return runtimeClass; }
    public String getEndpointEnvironment() { return endpointEnvironment; }
    public String getTransportMode() { return transportMode; }
    public Instant getReportedAt() { return reportedAt; }
    public Instant getReceivedAt() { return receivedAt; }
    public String getPayloadHash() { return payloadHash; }
}
