package com.tpverp.saas.license;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "saas_company")
public class SaasCompany {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(name = "tax_id", nullable = false, unique = true)
    private String taxId;

    @Enumerated(EnumType.STRING)
    @Column(name = "taxpayer_type", nullable = false)
    private TaxpayerType taxpayerType;

    @Enumerated(EnumType.STRING)
    @Column(name = "tax_regime", nullable = false)
    private TaxRegime taxRegime;

    @Enumerated(EnumType.STRING)
    @Column(name = "commercial_profile", nullable = false)
    private CommercialProfile commercialProfile = CommercialProfile.MAYORISTA;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected SaasCompany() {
    }

    public SaasCompany(UUID id, String name, String taxId, TaxpayerType taxpayerType, TaxRegime taxRegime, Instant createdAt) {
        this(id, name, taxId, taxpayerType, taxRegime, CommercialProfile.MAYORISTA, createdAt);
    }

    public SaasCompany(UUID id, String name, String taxId, TaxpayerType taxpayerType,
            TaxRegime taxRegime, CommercialProfile commercialProfile, Instant createdAt) {
        this.id = id;
        this.name = name;
        this.taxId = taxId;
        this.taxpayerType = taxpayerType;
        this.taxRegime = taxRegime;
        this.commercialProfile = commercialProfile == null
                ? CommercialProfile.MAYORISTA : commercialProfile;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getTaxId() {
        return taxId;
    }

    public TaxpayerType getTaxpayerType() {
        return taxpayerType;
    }

    public TaxRegime getTaxRegime() {
        return taxRegime;
    }

    public CommercialProfile getCommercialProfile() {
        return commercialProfile;
    }

    public void updateData(String name, TaxpayerType taxpayerType, TaxRegime taxRegime) {
        updateData(name, taxpayerType, taxRegime, CommercialProfile.MAYORISTA);
    }

    public void updateData(String name, TaxpayerType taxpayerType, TaxRegime taxRegime,
            CommercialProfile commercialProfile) {
        this.name = name;
        this.taxpayerType = taxpayerType;
        this.taxRegime = taxRegime;
        this.commercialProfile = commercialProfile == null
                ? CommercialProfile.MAYORISTA : commercialProfile;
    }
}
