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

    static ResolvedDocumentTemplate builtIn(
            DocumentTemplateType type, DocumentTemplateFormat format) {
        if (!format.supports(type)) {
            throw new IllegalArgumentException("document_template_format_not_supported");
        }
        String code = switch (type) {
            case FACTURA_VENTA -> format == DocumentTemplateFormat.A4
                    ? "FACTURA_A4" : "FACTURA_TICKET_80";
            case ALBARAN_VENTA -> "ALBARAN_A4";
            case TICKET -> "TICKET_80";
            case VALE -> "VALE_TICKET_80";
            default -> "INTEGRATED_" + type.name() + "_" + format.name();
        };
        return new ResolvedDocumentTemplate(
                null,
                type,
                format,
                DocumentTemplateScope.SYSTEM,
                code,
                1,
                SafeJrxmlCompiler.DATA_SCHEMA_VERSION,
                type == DocumentTemplateType.TICKET
                        ? "builtin:ticket"
                        : "builtin:" + type.name().toLowerCase(java.util.Locale.ROOT)
                                + ":" + format.name().toLowerCase(java.util.Locale.ROOT),
                null,
                true);
    }

    static ResolvedDocumentTemplate builtInTicket() {
        return builtIn(DocumentTemplateType.TICKET, DocumentTemplateFormat.TICKET_80);
    }

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

}
