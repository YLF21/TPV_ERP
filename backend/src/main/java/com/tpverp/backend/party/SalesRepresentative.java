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
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "comercial")
public class SalesRepresentative {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "empresa_id", nullable = false)
    private Company company;

    @Column(name = "commercial_id", nullable = false, length = 9, updatable = false)
    private String commercialId;

    @Column(nullable = false)
    private String nombre;

    @Column(length = 64)
    private String telefono;

    @Column(length = 320)
    private String email;

    @Column(name = "otro_contacto")
    private String otherContact;

    @Version
    private long version;

    protected SalesRepresentative() {
    }

    public SalesRepresentative(
            Company company,
            String name,
            String phone,
            String email,
            String otherContact) {
        this.id = UUID.randomUUID();
        this.company = Objects.requireNonNull(company, "empresa");
        update(name, phone, email, otherContact);
    }

    public void update(String name, String phone, String email, String otherContact) {
        this.nombre = PartyValues.required(name, "nombre");
        this.telefono = PartyValues.optional(phone);
        this.email = PartyValues.optional(email);
        this.otherContact = PartyValues.optional(otherContact);
    }

    public UUID getId() {
        return id;
    }

    public void assignCode(String code) {
        if (commercialId != null) {
            throw new IllegalStateException("El codigo de comercial es inmutable");
        }
        commercialId = PartyValues.required(code, "commercialId");
    }

    public String getCommercialId() {
        return commercialId;
    }

    public String getName() {
        return nombre;
    }

    public String getPhone() {
        return telefono;
    }

    public String getEmail() {
        return email;
    }

    public String getOtherContact() {
        return otherContact;
    }
}
