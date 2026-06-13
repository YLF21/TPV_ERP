package com.tpverp.backend.party;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Embeddable
public class SupplierRepresentativeId implements Serializable {

    @Column(name = "proveedor_id")
    private UUID supplierId;

    @Column(name = "comercial_id")
    private UUID representativeId;

    protected SupplierRepresentativeId() {
    }

    public SupplierRepresentativeId(UUID supplierId, UUID representativeId) {
        this.supplierId = Objects.requireNonNull(supplierId, "proveedorId");
        this.representativeId = Objects.requireNonNull(representativeId, "comercialId");
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof SupplierRepresentativeId value
                && supplierId.equals(value.supplierId)
                && representativeId.equals(value.representativeId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(supplierId, representativeId);
    }
}
