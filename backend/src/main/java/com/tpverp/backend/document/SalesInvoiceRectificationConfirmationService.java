package com.tpverp.backend.document;

import java.util.Objects;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SalesInvoiceRectificationConfirmationService {

    private final SalesInvoiceRectificationService rectifications;
    private final DocumentService documents;

    public SalesInvoiceRectificationConfirmationService(
            SalesInvoiceRectificationService rectifications,
            DocumentService documents) {
        this.rectifications = rectifications;
        this.documents = documents;
    }

    @Transactional
    public SalesInvoiceRectificationService.Details confirm(
            UUID documentId,
            Authentication authentication) {
        var id = Objects.requireNonNull(documentId, "documentId");
        var current = rectifications.details(id);
        if (current.document().getTipo() != CommercialDocumentType.RECTIFICATIVA_VENTA) {
            throw new IllegalArgumentException(
                    "El documento no es una factura rectificativa de venta");
        }
        documents.confirm(id, authentication);
        return rectifications.details(id);
    }
}
