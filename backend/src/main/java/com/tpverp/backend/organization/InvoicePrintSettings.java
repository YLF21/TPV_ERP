package com.tpverp.backend.organization;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.UUID;

@Entity
@Table(name = "configuracion_impresion_factura")
public class InvoicePrintSettings {

    @Id
    @Column(name = "empresa_id")
    private UUID companyId;

    @Column(length = 2000)
    private String observaciones;

    @Version
    private long version;

    protected InvoicePrintSettings() {
    }

    public InvoicePrintSettings(UUID companyId) {
        this.companyId = java.util.Objects.requireNonNull(companyId, "companyId");
    }

    public UUID getCompanyId() {
        return companyId;
    }

    public String getObservaciones() {
        return observaciones;
    }

    public void updateObservations(String value) {
        String normalized = value == null || value.isBlank() ? null : value.trim();
        if (normalized != null && normalized.length() > 2000) {
            throw new IllegalArgumentException("invoice_print_observations_too_long");
        }
        observaciones = normalized;
    }
}
