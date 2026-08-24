package com.tpverp.backend.document.template;

import com.tpverp.backend.audit.AuditResult;
import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.organization.CurrentOrganization;
import java.util.LinkedHashMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DocumentTemplatePresentationService {

    private final CurrentOrganization organization;
    private final DocumentTemplatePresentationSettingRepository settings;
    private final DocumentTemplateResolver templates;
    private final AuditService audit;

    public DocumentTemplatePresentationService(
            CurrentOrganization organization,
            DocumentTemplatePresentationSettingRepository settings,
            DocumentTemplateResolver templates,
            AuditService audit) {
        this.organization = organization;
        this.settings = settings;
        this.templates = templates;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public Presentation presentation(
            DocumentTemplateType type, DocumentTemplateFormat format) {
        requireImplemented(type);
        DocumentTemplatePresentationSetting.requireSupported(type, format);
        return new Presentation(type, format, origin(type, format));
    }

    @Transactional(readOnly = true)
    public DocumentTemplateOrigin origin(
            DocumentTemplateType type, DocumentTemplateFormat format) {
        requireImplemented(type);
        DocumentTemplatePresentationSetting.requireSupported(type, format);
        return settings.findByStoreIdAndTypeAndFormat(
                        organization.currentStore().getId(), type, format)
                .map(DocumentTemplatePresentationSetting::getOrigin)
                .orElse(DocumentTemplateOrigin.INTEGRATED);
    }

    @Transactional
    public Presentation update(
            DocumentTemplateType type,
            DocumentTemplateFormat format,
            DocumentTemplateOrigin origin) {
        requireImplemented(type);
        DocumentTemplatePresentationSetting.requireSupported(type, format);
        var store = organization.currentStore();
        if (origin == DocumentTemplateOrigin.IMPORTED
                && templates.findEffective(store, type, format)
                        .filter(template -> !template.builtIn())
                        .isEmpty()) {
            throw new DocumentTemplateRequiredException(type, format);
        }
        var value = settings.findByStoreIdAndTypeAndFormat(store.getId(), type, format)
                .orElseGet(() -> new DocumentTemplatePresentationSetting(
                        store.getId(), type, format));
        value.useOrigin(origin);
        settings.save(value);

        var details = new LinkedHashMap<String, Object>();
        details.put("storeId", store.getId().toString());
        details.put("type", type.name());
        details.put("format", format.name());
        details.put("origin", origin.name());
        audit.record("DOCUMENT_TEMPLATE_PRESENTATION_UPDATED", AuditResult.EXITO, details);
        return new Presentation(type, format, origin);
    }

    private static void requireImplemented(DocumentTemplateType type) {
        if (type == null) {
            throw new IllegalArgumentException(
                    "document_template_presentation_not_supported");
        }
    }

    public record Presentation(
            DocumentTemplateType type,
            DocumentTemplateFormat format,
            DocumentTemplateOrigin origin) {
    }
}
