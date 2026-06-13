package com.tpverp.backend.party;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;
import java.util.Objects;

@Entity
@Table(name = "proveedor_comercial")
public class SupplierRepresentative {

    @EmbeddedId
    private SupplierRepresentativeId id;

    @MapsId("supplierId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "proveedor_id", nullable = false)
    private Supplier supplier;

    @MapsId("representativeId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "comercial_id", nullable = false)
    private SalesRepresentative representative;

    @Column(nullable = false)
    private boolean principal;

    protected SupplierRepresentative() {
    }

    SupplierRepresentative(Supplier supplier, SalesRepresentative representative) {
        this.supplier = Objects.requireNonNull(supplier, "proveedor");
        this.representative = Objects.requireNonNull(representative, "comercial");
        this.id = new SupplierRepresentativeId(supplier.getId(), representative.getId());
    }

    public boolean isPrimary() {
        return principal;
    }

    public SalesRepresentative getRepresentative() {
        return representative;
    }

    void setPrimary(boolean primary) {
        this.principal = primary;
    }
}
