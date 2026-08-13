package com.tpverp.backend.document.template;

import java.util.UUID;

public record ResolvedDocumentTemplate(
        UUID id,
        DocumentTemplateType type,
        DocumentTemplateFormat format,
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
                template.getFormat(),
                template.getScope(),
                template.getCode(),
                template.getTemplateVersion(),
                template.getSchemaVersion(),
                template.getArtifactReference(),
                template.getSha256(),
                false);
    }

    static ResolvedDocumentTemplate builtIn(DocumentTemplateType type) {
        return builtIn(type, DocumentTemplateFormat.defaultFor(type));
    }

    static ResolvedDocumentTemplate builtIn(
            DocumentTemplateType type, DocumentTemplateFormat format) {
        if (type == DocumentTemplateType.FACTURA_VENTA
                && format == DocumentTemplateFormat.TICKET_80) {
            return new ResolvedDocumentTemplate(
                    null,
                    type,
                    format,
                    DocumentTemplateScope.SYSTEM,
                    "FACTURA_TICKET_80",
                    1,
                    1,
                    "builtin:factura_venta_ticket_80_v1",
                    null,
                    true);
        }
        if (format != DocumentTemplateFormat.defaultFor(type)) {
            throw new IllegalArgumentException("document_template_format_unsupported");
        }
        return switch (type) {
            case FACTURA_VENTA -> new ResolvedDocumentTemplate(
                    null,
                    type,
                    format,
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
                    format,
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
                    format,
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
