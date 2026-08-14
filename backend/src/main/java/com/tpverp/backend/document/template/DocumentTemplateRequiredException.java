package com.tpverp.backend.document.template;

public final class DocumentTemplateRequiredException extends IllegalStateException {

    public static final String CODE = "DOCUMENT_TEMPLATE_JRXML_REQUIRED";
    public static final String MESSAGE_KEY = "message.document_template.jrxml_required";

    private final DocumentTemplateType documentType;
    private final DocumentTemplateFormat format;

    public DocumentTemplateRequiredException(
            DocumentTemplateType documentType,
            DocumentTemplateFormat format) {
        super(MESSAGE_KEY);
        this.documentType = java.util.Objects.requireNonNull(documentType, "documentType");
        this.format = java.util.Objects.requireNonNull(format, "format");
    }

    public DocumentTemplateType documentType() {
        return documentType;
    }

    public DocumentTemplateFormat format() {
        return format;
    }
}
