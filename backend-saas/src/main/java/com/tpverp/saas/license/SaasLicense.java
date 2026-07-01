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
import java.util.UUID;

@Entity
@Table(name = "saas_license")
public class SaasLicense {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "company_id", nullable = false)
    private SaasCompany company;

    @Column(nullable = false, unique = true)
    private String reference;

    @Column(name = "valid_until", nullable = false)
    private Instant validUntil;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private LicenseSaasStatus status;

    @Column(name = "max_windows", nullable = false)
    private int maxWindows;

    @Column(name = "max_pda", nullable = false)
    private int maxPda;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected SaasLicense() {
    }

    public SaasLicense(
            UUID id,
            SaasCompany company,
            String reference,
            Instant validUntil,
            int maxWindows,
            int maxPda,
            Instant createdAt) {
        this.id = id;
        this.company = company;
        this.reference = reference;
        this.validUntil = validUntil;
        this.status = LicenseSaasStatus.VALIDA;
        this.maxWindows = maxWindows;
        this.maxPda = maxPda;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public SaasCompany getCompany() {
        return company;
    }

    public String getReference() {
        return reference;
    }

    public Instant getValidUntil() {
        return validUntil;
    }

    public LicenseSaasStatus getStatus() {
        return status;
    }

    public int getMaxWindows() {
        return maxWindows;
    }

    public int getMaxPda() {
        return maxPda;
    }

    public void renew(Instant validUntil, int maxWindows, int maxPda) {
        this.validUntil = validUntil;
        this.maxWindows = Math.max(1, maxWindows);
        this.maxPda = Math.max(0, maxPda);
    }

    public void block() {
        status = LicenseSaasStatus.BLOQUEADA_MANUAL;
    }

    public void unblock() {
        status = LicenseSaasStatus.VALIDA;
    }
}
