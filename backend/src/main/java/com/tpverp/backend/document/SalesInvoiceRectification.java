package com.tpverp.backend.document;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "factura_rectificacion_venta")
public class SalesInvoiceRectification {

    @Id
    @Column(name = "documento_id")
    private UUID documentId;
    @Column(name = "origen_documento_id", nullable = false)
    private UUID originalDocumentId;
    @Enumerated(EnumType.STRING)
    @Column(name = "tipo_fiscal", nullable = false, length = 2)
    private SalesInvoiceRectificationFiscalType fiscalType;
    @Enumerated(EnumType.STRING)
    @Column(name = "metodo", nullable = false, length = 1)
    private SalesInvoiceRectificationMethod method;
    @Enumerated(EnumType.STRING)
    @Column(name = "motivo", nullable = false, length = 40)
    private SalesInvoiceRectificationReason reason;
    @Column(name = "detalle", nullable = false)
    private String detail;
    @Column(name = "afecta_stock", nullable = false)
    private boolean affectsStock;
    @Column(name = "creado_en", nullable = false)
    private Instant createdAt;
    @Version
    private long version;

    protected SalesInvoiceRectification() {
    }

    public SalesInvoiceRectification(
            UUID documentId,
            UUID originalDocumentId,
            SalesInvoiceRectificationReason reason,
            String detail,
            Instant createdAt) {
        this.documentId = Objects.requireNonNull(documentId, "documentId");
        this.originalDocumentId = Objects.requireNonNull(originalDocumentId, "originalDocumentId");
        if (documentId.equals(originalDocumentId)) {
            throw new IllegalArgumentException("Una factura no puede rectificarse a si misma");
        }
        changeReason(reason, detail);
        this.method = SalesInvoiceRectificationMethod.I;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    public void changeReason(SalesInvoiceRectificationReason reason, String detail) {
        this.reason = Objects.requireNonNull(reason, "reason");
        this.fiscalType = reason.fiscalType();
        this.affectsStock = reason.affectsStock();
        this.detail = requiredDetail(detail);
    }

    public UUID getDocumentId() {
        return documentId;
    }

    public UUID getOriginalDocumentId() {
        return originalDocumentId;
    }

    public SalesInvoiceRectificationFiscalType getFiscalType() {
        return fiscalType;
    }

    public SalesInvoiceRectificationMethod getMethod() {
        return method;
    }

    public SalesInvoiceRectificationReason getReason() {
        return reason;
    }

    public String getDetail() {
        return detail;
    }

    public boolean isAffectsStock() {
        return affectsStock;
    }

    private static String requiredDetail(String value) {
        var normalized = value == null ? "" : value.trim();
        if (normalized.length() < 10 || normalized.length() > 500) {
            throw new IllegalArgumentException(
                    "El motivo detallado debe tener entre 10 y 500 caracteres");
        }
        return normalized;
    }
}
