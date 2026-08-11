package com.tpverp.backend.document.template;

import java.util.UUID;

public record ResolvedDocumentTemplate(
        UUID id,
        DocumentTemplateType type,
        DocumentTemplateScope scope,
        String code,
        int version,
        int schemaVersion,
        String artifactReference,
        String sha256,
        boolean builtIn) {

    static ResolvedDocumentTemplate from(DocumentTemplate template) {
        if (template.getStatus() != DocumentTemplateStatus.ACTIVE) {
            throw new IllegalArgumentException("document_template_not_active");
        }
        return new ResolvedDocumentTemplate(
                template.getId(),
                template.getType(),
                template.getScope(),
                template.getCode(),
                template.getTemplateVersion(),
                template.getSchemaVersion(),
                template.getArtifactReference(),
                template.getSha256(),
                false);
    }

    static ResolvedDocumentTemplate builtIn(DocumentTemplateType type) {
        return switch (type) {
            case FACTURA_VENTA -> new ResolvedDocumentTemplate(
                    null,
                    type,
                    DocumentTemplateScope.SYSTEM,
                    "FACTURA_A4",
                    1,
                    1,
                    "builtin:factura_venta_a4_v1",
                    null,
                    true);
            case ALBARAN_VENTA -> new ResolvedDocumentTemplate(
                    null,
                    type,
                    DocumentTemplateScope.SYSTEM,
                    "ALBARAN_A4",
                    1,
                    1,
                    "builtin:albaran_venta_a4_v1",
                    null,
                    true);
            case TICKET -> new ResolvedDocumentTemplate(
                    null,
                    type,
                    DocumentTemplateScope.SYSTEM,
                    "TICKET_80",
                    1,
                    1,
                    "builtin:ticket_80_v1",
                    null,
                    true);
        };
    }
}
