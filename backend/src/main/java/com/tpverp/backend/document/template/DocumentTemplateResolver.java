package com.tpverp.backend.document.template;

import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DocumentTemplateResolver {

    private final DocumentTemplateRepository templates;
    private final CurrentOrganization organization;

    public DocumentTemplateResolver(
            DocumentTemplateRepository templates,
            CurrentOrganization organization) {
        this.templates = templates;
        this.organization = organization;
    }

    @Transactional(readOnly = true)
    public ResolvedDocumentTemplate resolve(DocumentTemplateType type) {
        return resolve(type, DocumentTemplateFormat.defaultFor(type));
    }

    @Transactional(readOnly = true)
    public ResolvedDocumentTemplate resolve(
            DocumentTemplateType type, DocumentTemplateFormat format) {
        return resolve(organization.currentStore(), type, format);
    }

    @Transactional(readOnly = true)
    public ResolvedDocumentTemplate resolve(Store store, DocumentTemplateType type) {
        return resolve(store, type, DocumentTemplateFormat.defaultFor(type));
    }

    @Transactional(readOnly = true)
    public ResolvedDocumentTemplate resolve(
            Store store, DocumentTemplateType type, DocumentTemplateFormat format) {
        var storeTemplate = templates.findActiveForStore(store.getId(), type, format);
        if (storeTemplate.isPresent()) {
            return ResolvedDocumentTemplate.from(storeTemplate.get());
        }
        var companyTemplate = templates.findActiveForCompany(
                store.getEmpresa().getId(), type, format);
        if (companyTemplate.isPresent()) {
            return ResolvedDocumentTemplate.from(companyTemplate.get());
        }
        return templates.findActiveForSystem(type, format)
                .map(ResolvedDocumentTemplate::from)
                .orElseGet(() -> ResolvedDocumentTemplate.builtIn(type, format));
    }
}
