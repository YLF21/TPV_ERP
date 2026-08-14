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

}
