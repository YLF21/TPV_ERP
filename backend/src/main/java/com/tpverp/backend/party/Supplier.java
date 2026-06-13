package com.tpverp.backend.party;

import com.tpverp.backend.organization.Empresa;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "proveedor")
public class Supplier {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "empresa_id", nullable = false)
    private Empresa company;

    @Column(name = "razon_social", nullable = false)
    private String legalName;

    @Column(name = "nombre_comercial")
    private String tradeName;

    @Enumerated(EnumType.STRING)
    @Column(name = "tipo_documento", nullable = false, length = 16)
    private DocumentType documentType;

    @Column(name = "numero_documento", nullable = false, length = 64)
    private String documentNumber;

    @Embedded
    private FiscalAddress fiscalAddress;

    @Column(length = 64)
    private String telefono;

    @Column(length = 320)
    private String email;

    @Column(columnDefinition = "text")
    private String observaciones;

    @Column(nullable = false)
    private boolean activo = true;

    @OneToMany(mappedBy = "supplier", orphanRemoval = true)
    private Set<SupplierRepresentative> representatives = new LinkedHashSet<>();

    @Version
    private long version;

    protected Supplier() {
    }

    public Supplier(
            Empresa company,
            String legalName,
            String tradeName,
            DocumentType documentType,
            String documentNumber,
            FiscalAddress fiscalAddress,
            String phone,
            String email,
            String notes) {
        this.id = UUID.randomUUID();
        this.company = Objects.requireNonNull(company, "empresa");
        update(legalName, tradeName, documentType, documentNumber,
                fiscalAddress, phone, email, notes);
    }

    public void update(
            String legalName,
            String tradeName,
            DocumentType documentType,
            String documentNumber,
            FiscalAddress fiscalAddress,
            String phone,
            String email,
            String notes) {
        this.legalName = PartyValues.required(legalName, "razonSocial");
        this.tradeName = PartyValues.optional(tradeName);
        this.documentType = Objects.requireNonNull(documentType, "tipoDocumento");
        this.documentNumber = PartyValues.document(documentNumber);
        this.fiscalAddress = fiscalAddress;
        this.telefono = PartyValues.optional(phone);
        this.email = PartyValues.optional(email);
        this.observaciones = PartyValues.optional(notes);
    }

    public SupplierRepresentative linkRepresentative(
            SalesRepresentative representative,
            boolean primary) {
        Objects.requireNonNull(representative, "comercial");
        SupplierRepresentative existing = representatives.stream()
                .filter(link -> link.getRepresentative().getId().equals(representative.getId()))
                .findFirst()
                .orElseGet(() -> {
                    var link = new SupplierRepresentative(this, representative);
                    representatives.add(link);
                    return link;
                });
        if (primary) {
            // El indice parcial de V3 permite un unico principal por proveedor.
            representatives.forEach(link -> link.setPrimary(false));
        }
        existing.setPrimary(primary);
        return existing;
    }

    public Set<SupplierRepresentative> getRepresentatives() {
        return Set.copyOf(representatives);
    }

    public void unlinkRepresentative(UUID representativeId) {
        representatives.removeIf(link ->
                link.getRepresentative().getId().equals(representativeId));
    }

    public void deactivate() {
        activo = false;
    }

    public UUID getId() {
        return id;
    }

    public String getLegalName() {
        return legalName;
    }

    public String getTradeName() {
        return tradeName;
    }

    public DocumentType getDocumentType() {
        return documentType;
    }

    public String getDocumentNumber() {
        return documentNumber;
    }

    public FiscalAddress getFiscalAddress() {
        return fiscalAddress;
    }

    public String getPhone() {
        return telefono;
    }

    public String getEmail() {
        return email;
    }

    public String getNotes() {
        return observaciones;
    }

    public boolean isActive() {
        return activo;
    }
}
