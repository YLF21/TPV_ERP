package com.tpverp.backend.party;

import com.tpverp.backend.organization.Company;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "commercial_contact_channel")
public class CommercialContactChannel {

    @Id
    private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "empresa_id", nullable = false)
    private Company company;
    @Column(nullable = false, length = 32)
    private String code;
    @Column(nullable = false, length = 64)
    private String name;
    @Column(nullable = false)
    private boolean active = true;
    @Version
    private long version;

    protected CommercialContactChannel() {
    }

    public CommercialContactChannel(Company company, String code, String name) {
        id = UUID.randomUUID();
        this.company = Objects.requireNonNull(company, "company");
        update(code, name, true);
    }

    public void update(String code, String name, boolean active) {
        this.code = PartyValues.required(code, "code").toUpperCase(Locale.ROOT);
        this.name = PartyValues.required(name, "name");
        this.active = active;
    }

    public UUID getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public boolean isActive() {
        return active;
    }
}
