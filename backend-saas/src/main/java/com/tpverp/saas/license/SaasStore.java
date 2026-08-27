package com.tpverp.saas.license;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "saas_store")
public class SaasStore {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "company_id", nullable = false)
    private SaasCompany company;

    @Column(nullable = false)
    private String code;

    @Column(nullable = false)
    private String name;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "store_address", columnDefinition = "jsonb")
    private Map<String, String> storeAddress;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "time_zone_id", nullable = false, length = 64)
    private String timeZoneId;

    protected SaasStore() {
    }

    public SaasStore(UUID id, SaasCompany company, String code, String name,
            String timeZoneId, Instant createdAt) {
        this(id, company, code, name, null, timeZoneId, createdAt);
    }

    public SaasStore(UUID id, SaasCompany company, String code, String name,
            Map<String, String> storeAddress, String timeZoneId, Instant createdAt) {
        this.id = id;
        this.company = company;
        this.code = LicenseProvisioningData.storeCode(code);
        this.name = name;
        this.storeAddress = storeAddress == null ? null : new LinkedHashMap<>(storeAddress);
        this.timeZoneId = LicenseProvisioningData.timeZoneId(timeZoneId);
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public SaasCompany getCompany() {
        return company;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public Map<String, String> getStoreAddress() {
        return storeAddress == null ? null : Map.copyOf(storeAddress);
    }

    public String getTimeZoneId() {
        return timeZoneId;
    }

    public void updateFiscalProvisioning(
            Map<String, String> storeAddress, String timeZoneId) {
        this.storeAddress = new LinkedHashMap<>(LicenseProvisioningData.fiscalAddress(
                storeAddress, "storeAddress"));
        this.timeZoneId = LicenseProvisioningData.timeZoneId(timeZoneId);
    }
}
