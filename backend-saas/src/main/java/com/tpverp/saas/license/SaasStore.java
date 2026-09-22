package com.tpverp.saas.license;

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
import java.math.BigDecimal;
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

    @Column(name = "internal_code", length = 7, insertable = false, updatable = false)
    private String internalCode;

    @Column(nullable = false)
    private boolean active = true;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "tax_regime", nullable = false)
    private TaxRegime taxRegime;

    @Enumerated(EnumType.STRING)
    @Column(name = "commercial_profile", nullable = false, length = 16)
    private CommercialProfile commercialProfile;

    @Column(name = "service_price", precision = 19, scale = 2)
    private BigDecimal servicePrice;

    @Enumerated(EnumType.STRING)
    @Column(name = "billing_period", length = 16)
    private StoreBillingPeriod billingPeriod;

    @Column(name = "max_windows", nullable = false)
    private int maxWindows = 1;

    @Column(name = "max_pda", nullable = false)
    private int maxPda;

    @Column(name = "valid_until")
    private Instant validUntil;

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
        this.taxRegime = company.getTaxRegime();
        this.commercialProfile = company.getCommercialProfile();
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

    public String getInternalCode() { return internalCode; }

    public boolean isActive() { return active; }

    public TaxRegime getTaxRegime() { return taxRegime; }

    public CommercialProfile getCommercialProfile() { return commercialProfile; }

    public BigDecimal getServicePrice() { return servicePrice; }

    public StoreBillingPeriod getBillingPeriod() { return billingPeriod; }

    public int getMaxWindows() { return maxWindows; }

    public int getMaxPda() { return maxPda; }

    public Instant getValidUntil() { return validUntil; }

    public void updateServicePrice(BigDecimal servicePrice, StoreBillingPeriod billingPeriod) {
        this.servicePrice = java.util.Objects.requireNonNull(servicePrice, "servicePrice");
        this.billingPeriod = java.util.Objects.requireNonNull(billingPeriod, "billingPeriod");
    }

    public void updateLicenseConfiguration(Instant validUntil, int maxWindows, int maxPda) {
        if (validUntil == null || maxWindows < 1 || maxPda < 0) {
            throw new IllegalArgumentException("Validez o cupos de licencia no validos");
        }
        this.validUntil = validUntil;
        this.maxWindows = maxWindows;
        this.maxPda = maxPda;
    }

    public void setTaxRegime(TaxRegime taxRegime) {
        this.taxRegime = java.util.Objects.requireNonNull(taxRegime, "taxRegime");
    }

    public void setCommercialProfile(CommercialProfile commercialProfile) {
        this.commercialProfile = java.util.Objects.requireNonNull(commercialProfile, "commercialProfile");
    }

    public void setActive(boolean active) { this.active = active; }

    public void updateAdministration(String name, Map<String, String> address, String timeZoneId, boolean active) {
        this.name = LicenseProvisioningData.requiredName(name, "name", 200);
        updateFiscalProvisioning(address, timeZoneId);
        this.active = active;
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
