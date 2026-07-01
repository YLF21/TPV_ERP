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

    @Column(name = "entity_type", nullable = false)
    private String entityType;

    @Column(name = "entity_id", nullable = false)
    private UUID entityId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SyncOperation operation;

    @Column(nullable = false, columnDefinition = "text")
    private String payload;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    protected SaasSyncEvent() {
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
            Instant receivedAt) {
        this.eventId = eventId;
        this.company = company;
        this.store = store;
        this.installation = installation;
        this.entityType = entityType;
        this.entityId = entityId;
        this.operation = operation;
        this.payload = payload;
        this.receivedAt = receivedAt;
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

    public Instant getReceivedAt() {
        return receivedAt;
    }
}
