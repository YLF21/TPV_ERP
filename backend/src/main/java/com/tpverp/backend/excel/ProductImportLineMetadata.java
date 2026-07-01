package com.tpverp.backend.excel;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "producto_importacion_excel_linea")
public class ProductImportLineMetadata {

    @Id
    private UUID id = UUID.randomUUID();
    @Column(name = "documento_id", nullable = false)
    private UUID documentId;
    @Column(name = "producto_id", nullable = false)
    private UUID productId;
    @Column(name = "referencia_proveedor", length = 128)
    private String supplierReference;
    @Version
    private long version;

    protected ProductImportLineMetadata() {
    }

    public ProductImportLineMetadata(UUID documentId, UUID productId, String supplierReference) {
        this.documentId = Objects.requireNonNull(documentId, "documentId");
        this.productId = Objects.requireNonNull(productId, "productId");
        this.supplierReference = supplierReference == null || supplierReference.isBlank()
                ? null
                : supplierReference.trim().toUpperCase(Locale.ROOT);
    }

    public UUID documentId() {
        return documentId;
    }

    public UUID productId() {
        return productId;
    }

    public String supplierReference() {
        return supplierReference;
    }
}
