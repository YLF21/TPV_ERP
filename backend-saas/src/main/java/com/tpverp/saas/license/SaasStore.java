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

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected SaasStore() {
    }

    public SaasStore(UUID id, SaasCompany company, String code, String name, Instant createdAt) {
        this.id = id;
        this.company = company;
        this.code = code;
        this.name = name;
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
}
