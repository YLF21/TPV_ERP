package com.tpverp.saas.sync;

import com.tpverp.saas.license.SaasCompany;
import com.tpverp.saas.license.SaasInstallation;
import com.tpverp.saas.license.SaasStore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "saas_sync_event")
public class SaasSyncEvent {

    @Id
    @Column(name = "event_id")
    private UUID eventId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "company_id", nullable = false)
    private SaasCompany company;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "store_id")
    private SaasStore store;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "installation_id")
    private SaasInstallation installation;

    @Column(name = "store_sequence")
    private Long storeSequence;

    @Column(name = "entity_type", nullable = false)
    private String entityType;

    @Column(name = "entity_id", nullable = false)
    private UUID entityId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SyncOperation operation;

    @Column(nullable = false, columnDefinition = "text")
    private String payload;

    @Column(name = "payload_hash", nullable = false, length = 64)
    private String payloadHash;

    @Column(name = "schema_version", nullable = false)
    private int schemaVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "projection_status", nullable = false, length = 24)
    private ProjectionStatus projectionStatus;

    @Column(name = "projected_at")
    private Instant projectedAt;

    @Column(name = "projection_error", columnDefinition = "text")
    private String projectionError;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    protected SaasSyncEvent() {
    }

    public SaasSyncEvent(
            UUID eventId,
            SaasCompany company,
            SaasStore store,
            SaasInstallation installation,
            Long storeSequence,
            String entityType,
            UUID entityId,
            SyncOperation operation,
            String payload,
            String payloadHash,
            int schemaVersion,
            Instant receivedAt) {
        this.eventId = eventId;
        this.company = company;
        this.store = store;
        this.installation = installation;
        this.storeSequence = storeSequence;
        this.entityType = entityType;
        this.entityId = entityId;
        this.operation = operation;
        this.payload = payload;
        this.payloadHash = payloadHash;
        this.schemaVersion = schemaVersion;
        this.projectionStatus = ProjectionStatus.RECEIVED;
        this.receivedAt = receivedAt;
    }

    public SaasSyncEvent(
            UUID eventId,
            SaasCompany company,
            SaasStore store,
            SaasInstallation installation,
            String entityType,
            UUID entityId,
            SyncOperation operation,
            String payload,
            String payloadHash,
            int schemaVersion,
            Instant receivedAt) {
        this(eventId, company, store, installation, null, entityType, entityId, operation,
                payload, payloadHash, schemaVersion, receivedAt);
    }

    public void markProjected(Instant now) {
        projectionStatus = ProjectionStatus.PROJECTED;
        projectedAt = now;
        projectionError = null;
    }

    public void markIgnored(Instant now) {
        projectionStatus = ProjectionStatus.IGNORED;
        projectedAt = now;
        projectionError = null;
    }

    public void markFailed(String error) {
        projectionStatus = ProjectionStatus.ERROR;
        projectedAt = null;
        projectionError = error == null || error.isBlank()
                ? "Error de proyeccion sin detalle"
                : error;
    }

    public void recordConflict(String error) {
        projectionError = error;
    }

    public UUID getEventId() {
        return eventId;
    }

    public SaasCompany getCompany() {
        return company;
    }

    public SaasStore getStore() {
        return store;
    }

    public SaasInstallation getInstallation() {
        return installation;
    }

    public Long getStoreSequence() {
        return storeSequence;
    }

    public String getEntityType() {
        return entityType;
    }

    public UUID getEntityId() {
        return entityId;
    }

    public SyncOperation getOperation() {
        return operation;
    }

    public String getPayload() {
        return payload;
    }

    public String getPayloadHash() {
        return payloadHash;
    }

    public int getSchemaVersion() {
        return schemaVersion;
    }

    public ProjectionStatus getProjectionStatus() {
        return projectionStatus;
    }

    public Instant getProjectedAt() {
        return projectedAt;
    }

    public String getProjectionError() {
        return projectionError;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public enum ProjectionStatus {
        RECEIVED,
        PROJECTED,
        IGNORED,
        ERROR
    }
}
